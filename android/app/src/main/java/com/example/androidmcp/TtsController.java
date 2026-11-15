package com.example.androidmcp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.UUID;

/** Text-to-speech output with explicit state; unavailable engines fail closed. */
public final class TtsController {
    private static final Object LOCK = new Object();
    private static TextToSpeech tts;
    private static String state = "idle"; // idle | initializing | ready | speaking | unavailable
    private static String lastError = "";
    private static String lastUtterance = "";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TtsController() { }

    public static JSONObject speak(Context context, String text, boolean flush, String language)
            throws ApiException {
        if (text == null || text.isEmpty() || text.length() > 4000) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid speech text");
        }
        TextToSpeech engine = ensure(context);
        String code = language == null || language.isEmpty() ? Locale.getDefault().getISO3Language() : language;
        try {
            Locale locale = language == null || language.isEmpty()
                    ? Locale.getDefault()
                    : Locale.forLanguageTag(language);
            if (locale.getISO3Language() != null) engine.setLanguage(locale);
        } catch (MissingResourceException ignored) { }
        String utterance = UUID.randomUUID().toString();
        int result = engine.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, utterance);
        if (result != TextToSpeech.SUCCESS) {
            throw new ApiException("TTS_SPEAK_FAILED", "The TTS engine rejected the utterance");
        }
        synchronized (LOCK) {
            state = "speaking";
            lastUtterance = utterance;
            lastError = "";
        }
        try {
            return new JSONObject()
                    .put("ok", true)
                    .put("utteranceId", utterance)
                    .put("language", code);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode TTS result");
        }
    }

    public static JSONObject stop() throws ApiException {
        TextToSpeech engine;
        synchronized (LOCK) { engine = tts; }
        if (engine != null) engine.stop();
        synchronized (LOCK) {
            if (!"unavailable".equals(state) && !"initializing".equals(state)) state = "ready";
            lastUtterance = "";
        }
        try {
            return new JSONObject().put("ok", true).put("stopped", true);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode TTS result");
        }
    }

    public static JSONObject status(Context context) throws ApiException {
        synchronized (LOCK) {
            try {
                return new JSONObject()
                        .put("state", state)
                        .put("available", !"unavailable".equals(state))
                        .put("engine", tts == null ? "" : String.valueOf(tts.getDefaultEngine()))
                        .put("utteranceId", lastUtterance)
                        .put("error", lastError);
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode TTS state");
            }
        }
    }

    private static TextToSpeech ensure(Context context) throws ApiException {
        synchronized (LOCK) {
            if ("ready".equals(state) || "speaking".equals(state)) return tts;
            if ("unavailable".equals(state)) {
                throw new ApiException("TTS_UNAVAILABLE", lastError.isEmpty()
                        ? "No TTS engine installed" : lastError);
            }
            if (tts != null) return tts;
            state = "initializing";
        }
        final Object done = new Object();
        final int[] initStatus = {TextToSpeech.ERROR};
        MAIN.post(() -> {
            TextToSpeech created = new TextToSpeech(context.getApplicationContext(), status -> {
                synchronized (done) {
                    initStatus[0] = status;
                    done.notifyAll();
                }
            });
            // The listener may fire before this assignment; publish regardless.
            synchronized (LOCK) { tts = created; }
        });
        synchronized (done) {
            try {
                done.wait(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (LOCK) {
            if (tts == null) {
                state = "unavailable";
                lastError = "TTS initialization timed out";
                throw new ApiException("TTS_UNAVAILABLE", lastError);
            }
            if (initStatus[0] == TextToSpeech.SUCCESS) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        synchronized (LOCK) { state = "speaking"; }
                    }
                    @Override public void onDone(String utteranceId) {
                        synchronized (LOCK) {
                            if (utteranceId.equals(lastUtterance)) { state = "ready"; lastUtterance = ""; }
                        }
                    }
                    @Override public void onError(String utteranceId) {
                        synchronized (LOCK) { state = "ready"; lastError = "TTS_ERROR"; }
                    }
                });
                state = "ready";
                lastError = "";
                return tts;
            }
            state = "unavailable";
            lastError = "No usable TTS engine";
            throw new ApiException("TTS_UNAVAILABLE", lastError);
        }
    }
}
