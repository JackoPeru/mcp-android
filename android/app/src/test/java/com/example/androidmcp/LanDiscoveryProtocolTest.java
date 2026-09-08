package com.example.androidmcp;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class LanDiscoveryProtocolTest {
    @Test public void responseEchoesNonceWithoutSensitiveFields() throws Exception {
        LanDiscoveryProtocol.Request request = LanDiscoveryProtocol.parseRequest(
                "{\"protocol\":\"mcp-android-discovery\",\"version\":1,\"nonce\":\"abc123\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("abc123", request.nonce);
        String response = new String(LanDiscoveryProtocol.encodeResponse(request,
                new TransportEndpoint("lan", "192.168.1.84", 8765, 24)), StandardCharsets.UTF_8);
        assertTrue(response.contains("\"nonce\":\"abc123\""));
        assertTrue(response.contains("\"address\":\"192.168.1.84\""));
        assertTrue(response.contains("\"port\":8765"));
        assertFalse(response.toLowerCase().contains("token"));
        assertFalse(response.toLowerCase().contains("device"));
    }

    @Test public void rejectsMalformedOversizedOrAmbiguousRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> LanDiscoveryProtocol.parseRequest(new byte[513]));
        assertThrows(IllegalArgumentException.class,
                () -> LanDiscoveryProtocol.parseRequest("[]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> LanDiscoveryProtocol.parseRequest(
                        "{\"protocol\":\"wrong\",\"version\":1,\"nonce\":\"abc\"}"
                                .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> LanDiscoveryProtocol.parseRequest(
                        "{\"protocol\":\"mcp-android-discovery\",\"version\":2,\"nonce\":\"abc\"}"
                                .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> LanDiscoveryProtocol.parseRequest(
                        "{\"protocol\":\"mcp-android-discovery\",\"version\":1,\"nonce\":\"\"}"
                                .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> LanDiscoveryProtocol.parseRequest(
                        "{\"protocol\":\"mcp-android-discovery\",\"version\":1,\"nonce\":\"abc\"}{}"
                                .getBytes(StandardCharsets.UTF_8)));
    }
}
