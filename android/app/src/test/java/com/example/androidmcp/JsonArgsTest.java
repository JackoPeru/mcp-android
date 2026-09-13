package com.example.androidmcp;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class JsonArgsTest {
    @Test public void integerParametersRejectLongValuesThatWouldWrap() throws Exception {
        JSONObject object = new JSONObject().put("value", 4_294_967_297L);
        assertThrows(ApiException.class, () -> JsonArgs.requiredInt(object, "value", 0, 100));
        assertEquals(7, JsonArgs.requiredInt(new JSONObject().put("value", 7), "value", 0, 100));
        assertEquals(9, JsonArgs.optionalInt(new JSONObject(), "value", 9, 0, 100));
    }
}
