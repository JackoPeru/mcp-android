package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LowPowerSessionPolicyTest {
    @Test public void stoppedSessionDisablesBackgroundEventDelivery() {
        assertEquals(0, LowPowerSessionPolicy.accessibilityEventTypes(false));
        assertFalse(LowPowerSessionPolicy.notificationListenerActive(false));
    }

    @Test public void activeSessionRestoresOnlyTrackedAccessibilityEvents() {
        int expected = McpAccessibilityService.trackedEventTypes();
        assertEquals(expected, LowPowerSessionPolicy.accessibilityEventTypes(true));
        assertTrue(LowPowerSessionPolicy.notificationListenerActive(true));
    }

    @Test public void vpnWatchdogIsRareFallbackNotPollingLoop() {
        assertEquals(15L * 60L * 1000L, LowPowerSessionPolicy.networkWatchdogMs());
    }
}
