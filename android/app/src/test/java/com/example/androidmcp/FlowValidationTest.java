package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

public final class FlowValidationTest {
    private static JSONObject step(String type) throws Exception {
        return new JSONObject().put("type", type).put("params", new JSONObject());
    }

    @Test public void acceptsBoundedNamedUiFlow() throws Exception {
        JSONObject flow = new JSONObject()
                .put("timeoutMs", 10_000)
                .put("steps", new JSONArray()
                        .put(step("find").put("capture", "continueButton"))
                        .put(step("click").put("ifPresent", new JSONObject().put("text", "Continue")))
                        .put(step("wait_idle"))
                        .put(step("observe").put("capture", "after")));
        FlowValidation.validate(flow);
    }

    @Test public void rejectsUnknownOrPrivilegedStepTypes() throws Exception {
        for (String type : new String[]{"shell", "file_delete", "javascript", "unknown"}) {
            JSONObject flow = new JSONObject().put("steps", new JSONArray().put(step(type)));
            assertThrows(ApiException.class, () -> FlowValidation.validate(flow));
        }
    }

    @Test public void rejectsTooManyStepsTimeoutAndAmbiguousGuards() throws Exception {
        JSONArray many = new JSONArray();
        for (int i = 0; i < 41; i++) many.put(step("observe"));
        assertThrows(ApiException.class,
                () -> FlowValidation.validate(new JSONObject().put("steps", many)));
        assertThrows(ApiException.class,
                () -> FlowValidation.validate(new JSONObject()
                        .put("timeoutMs", 20_001)
                        .put("steps", new JSONArray().put(step("observe")))));
        JSONObject guarded = step("click")
                .put("ifPresent", new JSONObject().put("text", "A"))
                .put("ifAbsent", new JSONObject().put("text", "B"));
        assertThrows(ApiException.class,
                () -> FlowValidation.validate(new JSONObject().put("steps", new JSONArray().put(guarded))));
    }

    @Test public void rejectsInvalidCaptureNames() throws Exception {
        JSONObject flow = new JSONObject().put("steps",
                new JSONArray().put(step("observe").put("capture", "../secret")));
        assertThrows(ApiException.class, () -> FlowValidation.validate(flow));
    }
}
