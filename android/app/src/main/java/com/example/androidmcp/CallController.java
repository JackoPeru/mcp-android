package com.example.androidmcp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

/** Calls: sanctioned dialer hand-off, plus user-confirmed direct call. */
public final class CallController {
    private CallController() { }

    static boolean canCall(Context context) {
        return context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
    }

    public static JSONObject dial(Context context, String number) throws ApiException {
        String value = SmsController.normalizeNumber(number);
        if (!SmsController.isValidNumber(value)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid phone number");
        }
        try {
            context.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + value))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new JSONObject().put("ok", true).put("dialer", true).put("number", value);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode result");
        } catch (RuntimeException e) {
            throw new ApiException("CALL_UNAVAILABLE", "No dialer available");
        }
    }

    public static JSONObject prepare(Context context, String number) throws ApiException {
        String value = SmsController.normalizeNumber(number);
        if (!SmsController.isValidNumber(value)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid phone number");
        }
        if (!canCall(context)) {
            throw new ApiException("CALL_PERMISSION_DENIED",
                    "Grant the phone permission, or use call_dial to open the dialer instead");
        }
        String id = PendingActions.create("call", value, "");
        return PendingActions.openConfirm(context, id, "call",
                "Chiama " + value, "Chiamata in uscita verso " + value);
    }

    public static JSONObject call(Context context, String confirmationId, String number)
            throws ApiException {
        String value = SmsController.normalizeNumber(number);
        if (!SmsController.isValidNumber(value)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid phone number");
        }
        if (!canCall(context)) throw new ApiException("CALL_PERMISSION_DENIED", "Grant the phone permission");
        if (!PendingActions.consumeConfirmed(confirmationId, "call", value, "")) {
            throw new ApiException("CONFIRMATION_REQUIRED",
                    "Prepare the call first and confirm it on the phone; confirmations are single-use");
        }
        try {
            context.startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + value))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new JSONObject().put("ok", true).put("calling", true).put("number", value);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode result");
        } catch (SecurityException e) {
            throw new ApiException("CALL_PERMISSION_DENIED", "Call permission revoked");
        } catch (RuntimeException e) {
            throw new ApiException("CALL_UNAVAILABLE", "No phone app available");
        }
    }
}
