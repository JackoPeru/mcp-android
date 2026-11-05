package com.example.androidmcp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AgentLinkTest {
    private static final String TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test public void messageEmbedsRealValuesAndStaysPortable() {
        String message = AgentLink.buildMessage("android", TOKEN,
                Arrays.asList("http://192.168.1.20:8765"),
                Arrays.asList("http://100.64.0.5:8765"));
        assertTrue(message.contains("server \"android\""));
        assertTrue(message.contains(TOKEN));
        assertTrue(message.contains("http://192.168.1.20:8765"));
        assertTrue(message.contains("http://100.64.0.5:8765"));
        assertTrue(message.contains("--write-host auto"));
        assertTrue(message.contains("android_status"));
        // Portable like the Desktop-AI console message: no absolute file paths
        // (http:// URLs are network locations, not file paths).
        String scrubbed = message.replace("http://", "").replace("https://", "");
        assertFalse(scrubbed.matches("(?s).*[A-Za-z]:[\\\\/].*"));
        assertFalse(message.contains("/home/"));
        assertFalse(message.contains("/Users/"));
        assertFalse(message.lines().anyMatch(line -> line.startsWith("/")));
    }

    @Test public void offlineMessageWhenNoEndpoints() {
        String message = AgentLink.buildMessage("android", TOKEN,
                Collections.emptyList(), Collections.emptyList());
        assertTrue(message.contains("Avvia controllo remoto"));
        assertFalse(message.contains(TOKEN));
    }

    @Test public void verifyNeedsARunningSession() {
        AgentLink.VerifyResult stopped = AgentLink.verify(false, 10, 15, 1000, "lan");
        assertFalse(stopped.ok);
        assertTrue(stopped.text.contains("Avvia controllo remoto"));
    }

    @Test public void verifyAcceptsFreshRpcsOrRecentOnes() {
        AgentLink.VerifyResult delta = AgentLink.verify(true, 10, 12, -1, "lan");
        assertTrue(delta.ok);
        assertTrue(delta.text.contains("Wi-Fi locale"));

        AgentLink.VerifyResult recent = AgentLink.verify(true, 12, 12, 30_000, "tailscale");
        assertTrue(recent.ok);
        assertTrue(recent.text.contains("Tailscale"));

        AgentLink.VerifyResult stale = AgentLink.verify(true, 12, 12, 10 * 60_000, "lan");
        assertFalse(stale.ok);
        assertTrue(stale.text.contains("android_status"));
    }

    @Test public void networkLabels() {
        assertEquals("Wi-Fi locale", AgentLink.networkLabel("lan"));
        assertEquals("Tailscale", AgentLink.networkLabel("tailscale"));
        assertEquals("rete disponibile", AgentLink.networkLabel("none"));
        assertEquals("pochi secondi fa", AgentLink.ageText(1000));
        assertEquals("42s fa", AgentLink.ageText(42_000));
        assertEquals("3m fa", AgentLink.ageText(180_000));
    }
}
