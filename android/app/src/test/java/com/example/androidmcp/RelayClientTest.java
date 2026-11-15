package com.example.androidmcp;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class RelayClientTest {
    private static final String TOKEN = "a".repeat(64);
    private static final String SECRET = "b".repeat(64);
    private static final String ROOM = "room-12345";
    private static final String CLIENT_NONCE = "00112233445566778899aabbccddeeff";
    private static final String SERVER_NONCE = "ffeeddccbbaa99887766554433221100";
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    @Test public void proofsAndSessionMatchNodeVectors() {
        assertEquals("1901039f5b0da741202f897ddaa7e7bae25fa99f77b1aabe82e971afbf9da716",
                RelayClient.authProof(SECRET, "phone", ROOM, CLIENT_NONCE));
        assertEquals("316e99f5430692fdca741a7909ac8991491ee6ae71b8ce2bd8c57d526ff15a96",
                RelayClient.okProof(SECRET, "phone", ROOM, CLIENT_NONCE, SERVER_NONCE));
        assertEquals("2b650a91ed32394ade5e6ed32481c0c3",
                RelayClient.sessionId(TOKEN, ROOM, CLIENT_NONCE, SERVER_NONCE));
    }

    @Test public void constantTimeCompareRejectsLengthsAndMismatches() {
        assertTrue(RelayClient.constantTimeEquals("abcd", "abcd"));
        assertFalse(RelayClient.constantTimeEquals("abcd", "abce"));
        assertFalse(RelayClient.constantTimeEquals("abcd", "abcde"));
        assertFalse(RelayClient.constantTimeEquals(null, "abcd"));
        assertFalse(RelayClient.constantTimeEquals("abcd", null));
    }

    @Test public void relayDomainIsSeparatedFromLanDomain() throws Exception {
        JSONObject relayEnvelope = LanSecureChannel.encryptRequest(
                TOKEN, SESSION, new JSONObject().put("method", "status"),
                LanSecureChannel.RELAY_DOMAIN);
        JSONObject decoded = LanSecureChannel.decryptRequest(
                TOKEN, SESSION, relayEnvelope, null, LanSecureChannel.RELAY_DOMAIN);
        assertEquals("status", decoded.getString("method"));
        // The same ciphertext must not authenticate under the LAN domain.
        assertThrows(IllegalArgumentException.class,
                () -> LanSecureChannel.decryptRequest(TOKEN, SESSION, relayEnvelope, null));
        assertThrows(IllegalArgumentException.class,
                () -> LanSecureChannel.decryptRequest(TOKEN, SESSION, relayEnvelope, null, "other-domain"));
    }

    @Test public void relayResponsesCarryFreshNonces() throws Exception {
        JSONObject one = LanSecureChannel.encryptResponseDomain(
                TOKEN, SESSION, new JSONObject().put("ok", true), LanSecureChannel.RELAY_DOMAIN);
        JSONObject two = LanSecureChannel.encryptResponseDomain(
                TOKEN, SESSION, new JSONObject().put("ok", true), LanSecureChannel.RELAY_DOMAIN);
        assertNotEquals(one.getString("nonce"), two.getString("nonce"));
    }
}
