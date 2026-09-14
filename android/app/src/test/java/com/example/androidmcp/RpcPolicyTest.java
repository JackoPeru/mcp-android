package com.example.androidmcp;

import org.junit.Test;
import org.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class RpcPolicyTest {
    @Test public void longRunningNonUiCallsDoNotHoldUiLock() {
        assertEquals(RpcPolicy.LockDomain.NONE, RpcPolicy.lockDomain("events_wait"));
        assertEquals(RpcPolicy.LockDomain.SHELL, RpcPolicy.lockDomain("shell"));
        assertEquals(RpcPolicy.LockDomain.SHELL, RpcPolicy.lockDomain("shizuku_shell"));
    }

    @Test public void fileAndUiCallsStaySerializedInTheirOwnDomains() {
        assertEquals(RpcPolicy.LockDomain.FILE, RpcPolicy.lockDomain("file_read"));
        assertEquals(RpcPolicy.LockDomain.FILE, RpcPolicy.lockDomain("file_write"));
        assertEquals(RpcPolicy.LockDomain.UI, RpcPolicy.lockDomain("ui_click"));
        assertEquals(RpcPolicy.LockDomain.UI, RpcPolicy.lockDomain("tap"));
        assertEquals(RpcPolicy.LockDomain.NONE, RpcPolicy.lockDomain("status"));
        assertEquals(RpcPolicy.LockDomain.NONE, RpcPolicy.lockDomain("events"));
    }

    @Test public void veilFollowsScreenWorkOnly() {
        assertTrue(RpcPolicy.showsVeil("screen_context"));
        assertTrue(RpcPolicy.showsVeil("screenshot"));
        assertTrue(RpcPolicy.showsVeil("ui_click"));
        assertTrue(RpcPolicy.showsVeil("tap"));
        assertTrue(RpcPolicy.showsVeil("act_and_observe"));
        assertTrue(RpcPolicy.showsVeil("flow"));
        assertFalse(RpcPolicy.showsVeil("status"));
        assertFalse(RpcPolicy.showsVeil("shell"));
        assertFalse(RpcPolicy.showsVeil("file_read"));
        assertFalse(RpcPolicy.showsVeil("diagnostics"));
        assertFalse(RpcPolicy.showsVeil("clipboard_get"));
        assertFalse(RpcPolicy.showsVeil("volume_set"));
        assertFalse(RpcPolicy.showsVeil("notifications"));
        assertFalse(RpcPolicy.showsVeil("ui_done"));
        assertTrue(RpcPolicy.showsVeil("unlock_device"));
    }

    @Test public void timedOutShizukuResultIsNeverReportedAsSuccess() throws Exception {
        JSONObject result = new JSONObject()
                .put("timedOut", true)
                .put("outcomeUnknown", true)
                .put("exitCode", JSONObject.NULL);
        ApiException error = assertThrows(ApiException.class,
                () -> RpcDispatcher.requireKnownShizukuOutcome(result));
        assertEquals("TIMEOUT", error.code);
    }
}
