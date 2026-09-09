package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TransportReconciliationTest {
    private static TransportEndpoint lan(String address, int prefix) {
        return new TransportEndpoint("lan", address, 8765, prefix);
    }

    private static TransportEndpoint tail(String address) {
        return new TransportEndpoint("tailscale", address, 8765, 10);
    }

    @Test public void lanLossDoesNotStopHealthyTailscale() {
        TransportReconciliation.Plan plan = TransportReconciliation.reconcile(
                lan("192.168.1.5", 24), tail("100.100.1.2"),
                null, tail("100.100.1.2"));
        assertTrue(plan.stopLan);
        assertFalse(plan.restartLan);
        assertFalse(plan.stopTailscale);
        assertFalse(plan.restartTailscale);
    }

    @Test public void dhcpChangeOnlyRestartsLan() {
        TransportReconciliation.Plan plan = TransportReconciliation.reconcile(
                lan("192.168.1.5", 24), tail("100.100.1.2"),
                lan("192.168.1.8", 24), tail("100.100.1.2"));
        assertTrue(plan.restartLan);
        assertFalse(plan.stopLan);
        assertFalse(plan.restartTailscale);
        assertFalse(plan.stopTailscale);
    }

    @Test public void missingListenerStartsWithoutRestartFlag() {
        TransportReconciliation.Plan plan = TransportReconciliation.reconcile(
                null, null, lan("10.0.0.5", 24), tail("100.90.0.4"));
        assertTrue(plan.startLan);
        assertTrue(plan.startTailscale);
        assertFalse(plan.restartLan);
        assertFalse(plan.restartTailscale);
    }

    @Test public void independentAddressChangesRestartBothWhenNeeded() {
        TransportReconciliation.Plan plan = TransportReconciliation.reconcile(
                lan("10.0.0.5", 24), tail("100.90.0.4"),
                lan("10.0.0.6", 24), tail("100.90.0.5"));
        assertTrue(plan.restartLan);
        assertTrue(plan.restartTailscale);
    }

    @Test public void discoveryRetriesOnlyWhenLanRpcExistsAndResponderIsDown() {
        TransportEndpoint activeLan = lan("192.168.1.5", 24);
        assertTrue(TransportReconciliation.shouldStartLanDiscovery(activeLan, false));
        assertFalse(TransportReconciliation.shouldStartLanDiscovery(activeLan, true));
        assertFalse(TransportReconciliation.shouldStartLanDiscovery(null, false));
    }
}
