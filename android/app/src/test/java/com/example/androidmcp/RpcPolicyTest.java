package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
