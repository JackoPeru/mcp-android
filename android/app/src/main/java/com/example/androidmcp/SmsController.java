package com.example.androidmcp;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.regex.Pattern;

/** SMS: user-confirmed sending plus optional inbox reading. */
public final class SmsController {
    // Digits plus the usual human separators; at least three digits are
    // enforced separately so "(02)" alone cannot pass.
    private static final Pattern NUMBER = Pattern.compile("^[+]?[0-9() -]{3,25}$");
    private static final int MAX_BODY = 1600;
    private static final int MAX_MESSAGES = 50;

    private SmsController() { }

    static boolean canSend(Context context) {
        return context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canRead(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    static String normalizeNumber(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    static boolean isValidNumber(String number) {
        if (number == null) return false;
        String value = number.trim();
        if (!NUMBER.matcher(value).matches()) return false;
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') digits++;
        }
        return digits >= 3;
    }

    public static JSONObject prepare(Context context, String to, String body) throws ApiException {
        String number = normalizeNumber(to);
        if (!isValidNumber(number)) throw new ApiException("INVALID_ARGUMENT", "Invalid phone number");
        if (body == null || body.isEmpty() || body.length() > MAX_BODY) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid SMS body");
        }
        if (!canSend(context)) {
            throw new ApiException("SMS_PERMISSION_DENIED",
                    "Grant the SMS permission, or use sms_open_composer to hand off to the Messages app");
        }
        String id = PendingActions.create("sms", number, body);
        return PendingActions.openConfirm(context, id, "sms", "Invia SMS a " + number, body);
    }

    public static JSONObject send(Context context, String confirmationId, String to, String body)
            throws ApiException {
        String number = normalizeNumber(to);
        if (!isValidNumber(number)) throw new ApiException("INVALID_ARGUMENT", "Invalid phone number");
        if (body == null || body.isEmpty() || body.length() > MAX_BODY) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid SMS body");
        }
        if (!canSend(context)) throw new ApiException("SMS_PERMISSION_DENIED", "Grant the SMS permission");
        if (!PendingActions.consumeConfirmed(confirmationId, "sms", number, body)) {
            throw new ApiException("CONFIRMATION_REQUIRED",
                    "Prepare the SMS first and confirm it on the phone; confirmations are single-use");
        }
        SmsManager manager = smsManager(context);
        ArrayList<String> parts = manager.divideMessage(body);
        // No delivery receiver: the platform call itself is the single attempt,
        // and we never retry automatically, so a duplicate SMS cannot happen.
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            sentIntents.add(PendingIntent.getBroadcast(context, 1000 + i,
                    new Intent("com.example.androidmcp.SMS_SENT"),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT));
        }
        try {
            manager.sendMultipartTextMessage(number, null, parts, sentIntents, null);
        } catch (RuntimeException e) {
            throw new ApiException("SMS_SEND_FAILED", "The platform rejected the SMS");
        }
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("status", "sent");
            result.put("to", number);
            result.put("parts", parts.size());
            result.put("note", "Handed to SmsManager once; delivery is not re-attempted");
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode SMS result");
        }
    }

    public static JSONObject openComposer(Context context, String to, String body) throws ApiException {
        String number = normalizeNumber(to);
        if (number.isEmpty() || !isValidNumber(number)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid phone number");
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
        if (body != null && !body.isEmpty()) intent.putExtra("sms_body", body);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            throw new ApiException("SMS_UNAVAILABLE", "No app can compose SMS");
        }
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("composer", true);
            result.put("to", number);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode result");
        }
    }

    public static JSONObject list(Context context, int limit, boolean unreadOnly) throws ApiException {
        if (!canRead(context)) {
            throw new ApiException("SMS_READ_PERMISSION_DENIED",
                    "Grant read-SMS permission (Android may restrict it; use notifications instead)");
        }
        int max = Math.max(1, Math.min(limit, MAX_MESSAGES));
        Uri uri = Uri.parse("content://sms/inbox");
        String selection = unreadOnly ? "read = 0" : null;
        JSONArray messages = new JSONArray();
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{"_id", "address", "body", "date", "read"},
                selection, null, "date DESC")) {
            if (cursor == null) throw new ApiException("SMS_READ_FAILED", "Inbox unavailable");
            int count = 0;
            while (cursor.moveToNext() && count < max) {
                messages.put(new JSONObject()
                        .put("id", cursor.getLong(0))
                        .put("from", cursor.getString(1) == null ? "" : cursor.getString(1))
                        .put("body", cursor.getString(2) == null ? "" : cursor.getString(2))
                        .put("date", cursor.getLong(3))
                        .put("read", cursor.getInt(4) == 1));
                count++;
            }
            return new JSONObject()
                    .put("messages", messages)
                    .put("count", messages.length())
                    .put("untrusted", true);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode inbox");
        } catch (SecurityException e) {
            throw new ApiException("SMS_READ_PERMISSION_DENIED", "Read-SMS permission revoked");
        }
    }

    private static SmsManager smsManager(Context context) throws ApiException {
        try {
            SmsManager manager = android.os.Build.VERSION.SDK_INT >= 31
                    ? context.getSystemService(SmsManager.class)
                    //noinspection deprecation
                    : SmsManager.getDefault();
            if (manager == null) throw new ApiException("SMS_UNAVAILABLE", "SMS service unavailable");
            return manager;
        } catch (RuntimeException e) {
            throw new ApiException("SMS_UNAVAILABLE", "SMS service unavailable");
        }
    }
}
