package com.example.androidmcp;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class HttpBoundaryTest {
    @Test public void serverFailuresAreNotReportedAsBadArguments() {
        assertEquals(500, McpHttpServer.httpStatus("INTERNAL"));
        assertEquals(500, McpHttpServer.httpStatus("RESPONSE_TOO_LARGE"));
        assertEquals(504, McpHttpServer.httpStatus("TIMEOUT"));
        assertEquals(400, McpHttpServer.httpStatus("INVALID_ARGUMENT"));
    }
    private ByteArrayInputStream input(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)); }
    @Test public void rejectsMissingWrongAndMalformedCredentials() {
        String token = "a".repeat(64);
        assertFalse(McpHttpServer.constantTimeBearer(null, token));
        assertFalse(McpHttpServer.constantTimeBearer("Bearer " + "b".repeat(64), token));
        assertFalse(McpHttpServer.constantTimeBearer("Bearer " + token + "extra", token));
        assertTrue(McpHttpServer.constantTimeBearer("Bearer " + token, token));
    }
    @Test public void rejectsAmbiguousAndOversizedHttpFraming() throws Exception {
        String prefix = "POST /rpc HTTP/1.1\r\nContent-Type: application/json\r\n";
        McpHttpServer.Headers headers = McpHttpServer.readHeaders(input(prefix + "Content-Length: 2\r\n\r\n"));
        assertEquals(2, headers.contentLength);
        for (String extra : new String[]{
                "Content-Length: 2\r\nContent-Length: 3\r\n", "Transfer-Encoding: chunked\r\nContent-Length: 2\r\n",
                "Content-Length: 65537\r\n", "Content-Length: -1\r\n", "Content-Length: +2\r\n"}) {
            assertThrows(McpHttpServer.HttpException.class, () -> McpHttpServer.readHeaders(input(prefix + extra + "\r\n")));
        }
        assertThrows(McpHttpServer.HttpException.class, () -> McpHttpServer.readHeaders(input("POST /rpc HTTP/1.1\n")));
    }
    @Test public void truncatedBodyFailsInsteadOfDispatchingPartialJson() throws Exception {
        assertThrows(McpHttpServer.HttpException.class, () -> McpHttpServer.readBody(input("{"), 2));
        assertArrayEquals("{}".getBytes(StandardCharsets.US_ASCII), McpHttpServer.readBody(input("{}extra"), 2));
    }
    @Test public void onlyLanEndpointUsesEncryptedWireEnvelope() {
        TransportEndpoint lan = new TransportEndpoint("lan", "192.168.1.84", 8765, 24);
        TransportEndpoint tailscale = new TransportEndpoint("tailscale", "100.100.1.2", 8765, 10);
        assertTrue(McpHttpServer.secureLanEndpoint(lan));
        assertFalse(McpHttpServer.secureLanEndpoint(tailscale));
        assertTrue(McpHttpServer.maxWireRequestBytes(lan) > SecurityValidators.MAX_JSON_BYTES);
        assertEquals(SecurityValidators.MAX_JSON_BYTES, McpHttpServer.maxWireRequestBytes(tailscale));
    }
}
