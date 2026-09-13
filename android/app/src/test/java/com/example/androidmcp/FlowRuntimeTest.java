package com.example.androidmcp;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class FlowRuntimeTest {
    @Test public void declaredActionMustFitRemainingDeadline() throws Exception {
        ApiException error = assertThrows(ApiException.class,
                () -> FlowRuntime.ensureActionFits("long_press",
                        new JSONObject().put("durationMs", 3_000), 2_999));
        assertEquals("TIMEOUT", error.code);
        assertThrows(ApiException.class,
                () -> FlowRuntime.ensureActionFits("click", new JSONObject(), 3_999));
        assertThrows(ApiException.class,
                () -> FlowRuntime.ensureActionFits("observe", new JSONObject(), 3_999));
        assertThrows(ApiException.class,
                () -> FlowRuntime.ensureActionFits("wait_idle", new JSONObject(), 3_999));
        FlowRuntime.ensureActionFits("click", new JSONObject(), 4_000);
        assertEquals(1_000, FlowRuntime.boundWaitTimeout(5_000, 5_000));
    }

    @Test public void mutatingActionTimeoutIsTerminalAndUncertain() throws Exception {
        assertTrue(FlowRuntime.timeoutStops("click", true));
        assertTrue(FlowRuntime.timeoutIsUnknown("click", true, true));
        assertTrue(!FlowRuntime.timeoutIsUnknown("click", true, false));
        assertTrue(FlowRuntime.timeoutStops("wait_idle", true));
        assertTrue(!FlowRuntime.timeoutIsUnknown("wait_idle", true, true));
        assertTrue(FlowRuntime.stepResultTimedOut(
                "scroll_to", new JSONObject().put("reason", "timeout")));
    }
}
