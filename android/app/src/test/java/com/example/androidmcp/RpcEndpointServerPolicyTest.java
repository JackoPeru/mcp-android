package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RpcEndpointServerPolicyTest {
    @Test public void lanClientPolicyAllowsOnlySameSubnet() {
        RpcEndpointServer.ClientPolicy policy = RpcEndpointServer.lanClientPolicy("192.168.1.84", 24);
        assertTrue(policy.allow("192.168.1.5"));
        assertFalse(policy.allow("192.168.2.5"));
        assertFalse(policy.allow("100.100.1.2"));
        assertFalse(policy.allow("8.8.8.8"));
    }

    @Test public void tailscaleClientPolicyAllowsOnlyCgnatPeers() {
        RpcEndpointServer.ClientPolicy policy = RpcEndpointServer.tailscaleClientPolicy();
        assertTrue(policy.allow("100.64.0.1"));
        assertTrue(policy.allow("100.127.255.254"));
        assertFalse(policy.allow("100.128.0.1"));
        assertFalse(policy.allow("192.168.1.5"));
    }

    @Test public void policiesRejectMalformedAddresses() {
        RpcEndpointServer.ClientPolicy lan = RpcEndpointServer.lanClientPolicy("10.0.0.5", 24);
        assertFalse(lan.allow("not-an-ip"));
        assertFalse(RpcEndpointServer.tailscaleClientPolicy().allow(""));
    }
}
