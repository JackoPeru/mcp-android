package com.example.androidmcp;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public final class FlowTraceTest {
    @Test public void traceMetadataDoesNotContainEnteredTextOrParams() throws Exception {
        JSONObject trace = FlowRuntime.stepTrace(2, "set_text", 31, true, "");
        assertEquals("set_text", trace.getString("type"));
        assertEquals(31, trace.getLong("durationMs"));
        assertFalse(trace.toString().contains("secret-password"));
        assertFalse(trace.has("params"));
    }
}
