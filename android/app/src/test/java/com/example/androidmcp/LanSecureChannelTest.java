package com.example.androidmcp;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public final class LanSecureChannelTest {
    private static final String TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    @Test public void decryptsNodeFixedVector() throws Exception {
        JSONObject envelope = new JSONObject()
                .put("version", 1)
                .put("session", SESSION)
                .put("nonce", "000102030405060708090a0b")
                .put("ciphertext", "LIOxGig8y1oNByNAujScLRmcbl9eG4vtBCKqDg339plq5pFDgNOfqVdhDBuE7+Y=");
        JSONObject request = LanSecureChannel.decryptRequest(TOKEN, SESSION, envelope, null);
        assertEquals("status", request.getString("method"));
        assertEquals(0, request.getJSONObject("params").length());
    }

    @Test public void helloProofMatchesNodeAndDoesNotContainToken() throws Exception {
        JSONObject hello = LanSecureChannel.helloResponse(TOKEN, "abc123", SESSION);
        assertEquals("02f516369a95008435bff033aeb42c6d3424a57996a5132a37cb86b3386e57c4",
                hello.getString("proof"));
        assertEquals(SESSION, hello.getString("session"));
        assertFalse(hello.toString().contains(TOKEN));
    }

    @Test public void rejectsReplayAndWrongSession() throws Exception {
        JSONObject envelope = new JSONObject()
                .put("version", 1)
                .put("session", SESSION)
                .put("nonce", "000102030405060708090a0b")
                .put("ciphertext", "LIOxGig8y1oNByNAujScLRmcbl9eG4vtBCKqDg339plq5pFDgNOfqVdhDBuE7+Y=");
        LanSecureChannel.ReplayGuard guard = new LanSecureChannel.ReplayGuard();
        LanSecureChannel.decryptRequest(TOKEN, SESSION, envelope, guard);
        assertThrows(IllegalArgumentException.class,
                () -> LanSecureChannel.decryptRequest(TOKEN, SESSION, envelope, guard));
        assertThrows(IllegalArgumentException.class,
                () -> LanSecureChannel.decryptRequest(TOKEN, "f".repeat(32), envelope, null));
    }
}
