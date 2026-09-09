package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LanDiscoveryRateLimitTest {
    @Test public void limitsEachSourceToOneResponsePerSecond() {
        LanDiscoveryResponder.SourceRateLimiter limiter = new LanDiscoveryResponder.SourceRateLimiter();
        assertTrue(limiter.allow("192.168.1.10", 1_000));
        assertFalse(limiter.allow("192.168.1.10", 1_999));
        assertTrue(limiter.allow("192.168.1.10", 2_000));
    }

    @Test public void sourceMapRemainsBoundedTo64Entries() {
        LanDiscoveryResponder.SourceRateLimiter limiter = new LanDiscoveryResponder.SourceRateLimiter();
        for (int i = 1; i <= 80; i++) {
            assertTrue(limiter.allow("10.0.0." + i, i * 2_000L));
        }
        assertEquals(64, limiter.size());
    }

    @Test public void responderBindsSubnetBroadcastSoLinuxAndroidCanReceiveBroadcastPackets() {
        TransportEndpoint lan = new TransportEndpoint("lan", "192.168.1.84", 8765, 24);
        assertEquals("192.168.1.255", LanDiscoveryResponder.bindAddress(lan));
    }
}
