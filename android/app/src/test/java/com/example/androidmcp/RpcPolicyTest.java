package com.example.androidmcp;

import org.junit.Test;
import org.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
