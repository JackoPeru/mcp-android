package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class NetworkAddressPolicyTest {
    @Test public void classifiesLanAndTailscaleWithoutOverlap() {
        assertTrue(NetworkAddressPolicy.isRfc1918("192.168.1.20"));
        assertTrue(NetworkAddressPolicy.isRfc1918("10.20.30.40"));
        assertTrue(NetworkAddressPolicy.isRfc1918("172.31.1.1"));
        assertFalse(NetworkAddressPolicy.isRfc1918("100.100.1.2"));
        assertFalse(NetworkAddressPolicy.isRfc1918("8.8.8.8"));
        assertTrue(NetworkAddressPolicy.isTailscale("100.64.0.1"));
        assertTrue(NetworkAddressPolicy.isTailscale("100.127.255.254"));
        assertFalse(NetworkAddressPolicy.isTailscale("100.128.0.1"));
    }

    @Test public void calculatesSubnetMembershipAndBroadcast() {
        assertTrue(NetworkAddressPolicy.subnetContains("192.168.1.84", 24, "192.168.1.10"));
        assertFalse(NetworkAddressPolicy.subnetContains("192.168.1.84", 24, "192.168.2.10"));
        assertEquals("192.168.1.255", NetworkAddressPolicy.broadcastAddress("192.168.1.84", 24));
    }

    @Test public void rejectsMalformedAddressesAndUnsafeLanPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> NetworkAddressPolicy.isRfc1918("192.168.1"));
        assertThrows(IllegalArgumentException.class, () -> NetworkAddressPolicy.isTailscale("100.64.0.999"));
        assertThrows(IllegalArgumentException.class, () -> NetworkAddressPolicy.broadcastAddress("192.168.1.5", 31));
    }

    @Test public void validatesTransportEndpoints() {
        TransportEndpoint lan = new TransportEndpoint("lan", "192.168.1.84", 8765, 24);
        assertEquals("lan", lan.transport);
        assertEquals("192.168.1.84", lan.address);
        assertEquals(8765, lan.port);
        assertEquals(24, lan.prefixLength);
        assertThrows(IllegalArgumentException.class,
                () -> new TransportEndpoint("public", "8.8.8.8", 8765, 24));
        assertThrows(IllegalArgumentException.class,
                () -> new TransportEndpoint("lan", "192.168.1.84", 0, 24));
    }
}
