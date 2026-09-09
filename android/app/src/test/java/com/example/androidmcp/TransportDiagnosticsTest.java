package com.example.androidmcp;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TransportDiagnosticsTest {
    private static TransportEndpoint lan(String address, int prefix) {
        return new TransportEndpoint("lan", address, 8765, prefix);
    }

    private static TransportEndpoint tail(String address) {
        return new TransportEndpoint("tailscale", address, 8765, 10);
    }

    @Test public void statusPrefersLanWhenBothAvailable() throws Exception {
        JSONObject status = TransportManager.encodeStatus(
                lan("192.168.1.84", 24), tail("100.100.1.2"), true);
        assertEquals("lan", status.getString("preferredTransport"));
        JSONObject endpoints = status.getJSONObject("endpoints");
        JSONObject lan = endpoints.getJSONObject("lan");
        JSONObject tailscale = endpoints.getJSONObject("tailscale");
        assertTrue(lan.getBoolean("available"));
        assertEquals("192.168.1.84", lan.getString("address"));
        assertEquals(24, lan.getInt("prefixLength"));
        assertTrue(lan.getBoolean("discovery"));
        assertTrue(tailscale.getBoolean("available"));
        assertEquals("100.100.1.2", tailscale.getString("address"));
        assertFalse(status.toString().toLowerCase().contains("token"));
    }

    @Test public void statusFallsBackToTailscaleAndRepresentsMissingLan() throws Exception {
        JSONObject status = TransportManager.encodeStatus(null, tail("100.90.1.2"), false);
        assertEquals("tailscale", status.getString("preferredTransport"));
        assertFalse(status.getJSONObject("endpoints").getJSONObject("lan").getBoolean("available"));
    }

    @Test public void statusReportsNoneWhenNoEndpointAvailable() throws Exception {
        JSONObject status = TransportManager.encodeStatus(null, null, false);
        assertEquals("none", status.getString("preferredTransport"));
    }
}
