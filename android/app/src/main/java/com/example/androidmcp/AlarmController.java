package com.example.androidmcp;

import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Alarms and timers through the sanctioned AlarmClock intents: the platform
 * clock app owns the schedule, no background alarms and no privileged APIs.
 */
public final class AlarmController {
    private AlarmController() { }

    public static JSONObject setAlarm(Context context, int hour, int minute, String label,
                                      boolean skipUi) throws ApiException {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid alarm time");
        }
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (label != null && !label.isEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            throw new ApiException("ALARM_UNAVAILABLE", "No clock app can set alarms");
        }
        try {
            return new JSONObject().put("ok", true).put("alarm", String.format(
                    java.util.Locale.US, "%02d:%02d", hour, minute));
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode alarm result");
        }
    }

    public static JSONObject setTimer(Context context, int seconds, String label,
                                      boolean skipUi) throws ApiException {
        if (seconds < 1 || seconds > 86_400) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid timer length");
        }
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (label != null && !label.isEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            throw new ApiException("ALARM_UNAVAILABLE", "No clock app can set timers");
        }
        try {
            return new JSONObject().put("ok", true).put("timerSeconds", seconds);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode timer result");
        }
    }
}
