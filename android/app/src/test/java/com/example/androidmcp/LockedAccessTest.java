package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LockedAccessTest {
    @Test public void fullAccessBlocksNothingCentrally() {
        for (String method : new String[]{"status", "file_read", "shell", "notifications",
                "unlock_device", "ui_done", "ui_click", "screen_context"}) {
            assertFalse(LockedAccess.blockedWhileLocked(method, true));
        }
    }

    @Test public void lockedDownKeepsOnlyNotificationsAndLifecycle() {
        assertFalse(LockedAccess.blockedWhileLocked("notifications", false));
        assertFalse(LockedAccess.blockedWhileLocked("unlock_device", false));
        assertFalse(LockedAccess.blockedWhileLocked("ui_done", false));
        for (String method : new String[]{"status", "file_read", "file_roots", "shell",
                "clipboard_get", "notification_dismiss", "diagnostics", "ui_click",
                "screen_context", "launch_app", null}) {
            assertTrue(LockedAccess.blockedWhileLocked(method, false));
        }
    }
}
