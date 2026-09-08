package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class NetworkRecoveryPolicyTest {
    @Test public void keepsHealthyBinding() {
        assertEquals(NetworkRecoveryPolicy.Action.KEEP,
                NetworkRecoveryPolicy.evaluate(true, "100.100.1.2", "100.100.1.2"));
    }

    @Test public void stopsSocketButKeepsServiceWhenVpnDisappears() {
        assertEquals(NetworkRecoveryPolicy.Action.STOP_AND_WAIT,
                NetworkRecoveryPolicy.evaluate(true, "100.100.1.2", ""));
        assertEquals(NetworkRecoveryPolicy.Action.WAIT,
                NetworkRecoveryPolicy.evaluate(false, "", ""));
    }

    @Test public void startsOrRestartsWhenVpnReturnsOrChanges() {
        assertEquals(NetworkRecoveryPolicy.Action.START,
                NetworkRecoveryPolicy.evaluate(false, "", "100.100.1.2"));
        assertEquals(NetworkRecoveryPolicy.Action.RESTART,
                NetworkRecoveryPolicy.evaluate(true, "100.100.1.2", "100.100.1.3"));
    }
}
