package com.example.androidmcp;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-shot user confirmations for outbound actions (SMS, direct call).
 *
 * <p>Design: the agent first calls prepare, which opens a visible confirmation
 * screen; only after the operator taps Conferma does send accept the same
 * confirmation id, once. Each entry is bound to its exact recipient/content,
 * expires after two minutes and is consumed before the platform call, so a
 * retry or a swapped payload can never send twice.
 */
public final class PendingActions {
    private static final long TTL_MS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();

    private PendingActions() { }

    static final class Pending {
        final String id;
        final String kind; // sms | call
        final String recipient;
        final String body;
        final long expiresAt;
        boolean confirmed;
        boolean used;

        Pending(String id, String kind, String recipient, String body) {
            this.id = id;
            this.kind = kind;
            this.recipient = recipient;
            this.body = body;
            this.expiresAt = System.currentTimeMillis() + TTL_MS;
        }
    }

    static synchronized String create(String kind, String recipient, String body) {
        purge();
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        StringBuilder id = new StringBuilder();
        for (byte value : random) id.append(String.format(java.util.Locale.US, "%02x", value));
        PENDING.put(id.toString(), new Pending(id.toString(), kind, recipient, body));
        return id.toString();
    }

    static synchronized Pending get(String id) {
        purge();
        Pending pending = id == null ? null : PENDING.get(id);
        if (pending == null || pending.expiresAt < System.currentTimeMillis()) return null;
        return pending;
    }

    static synchronized void confirm(String id) {
        Pending pending = get(id);
        if (pending != null) pending.confirmed = true;
    }

    static synchronized void cancel(String id) {
        if (id != null) PENDING.remove(id);
    }

    /** True exactly once: the caller may proceed with the platform action. */
    static synchronized boolean consumeConfirmed(String id, String kind,
                                                 String recipient, String body) {
        Pending pending = get(id);
        if (pending == null || pending.used || !pending.confirmed) return false;
        if (!pending.kind.equals(kind)) return false;
        if (!pending.recipient.equals(recipient)) return false;
        if (!pending.body.equals(body)) return false;
        pending.used = true;
        PENDING.remove(id);
        return true;
    }

    private static void purge() {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(entry -> entry.getValue().expiresAt < now || entry.getValue().used);
    }

    static JSONObject openConfirm(Context context, String pendingId, String kind,
                                 String title, String detail) throws ApiException {
        try {
            context.startActivity(new Intent(context, ConfirmActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("pendingId", pendingId)
                    .putExtra("kind", kind)
                    .putExtra("title", title)
                    .putExtra("detail", detail));
            return new JSONObject()
                    .put("ok", true)
                    .put("confirmationId", pendingId)
                    .put("expiresInMs", TTL_MS)
                    .put("state", "awaiting_user_confirmation");
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode confirmation");
        } catch (RuntimeException e) {
            throw new ApiException("CONFIRM_UNAVAILABLE", "Unable to open the confirmation screen");
        }
    }
}
