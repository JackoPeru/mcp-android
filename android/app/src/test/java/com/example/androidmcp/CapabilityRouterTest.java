package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CapabilityRouterTest {
    @Test public void privilegedNamedOperationsNeverFallBackToTermux() {
        assertEquals("unavailable", CapabilityRouter.privilegedBackend(false, true));
        assertEquals("shizuku", CapabilityRouter.privilegedBackend(true, false));
        assertEquals("shizuku", CapabilityRouter.privilegedBackend(true, true));
    }

    @Test public void logRedactionRemovesBearerAndOpenAiStyleSecrets() {
        String input = "Authorization: Bearer " + "a".repeat(64)
                + "\nkey=sk-proj-abcdefghijklmnopqrstuvwxyz1234567890";
        String redacted = CapabilityRouter.redactLogText(input);
        assertFalse(redacted.contains("a".repeat(64)));
        assertFalse(redacted.contains("sk-proj-"));
    }

    @Test public void capabilityMetadataKeepsPrivilegedActionsSeparate() throws Exception {
        java.lang.reflect.Method method = CapabilityRouter.class
                .getDeclaredMethod("operationClasses");
        method.setAccessible(true);
        org.json.JSONObject classes = (org.json.JSONObject) method.invoke(null);
        String destructive = classes.getJSONArray("destructive").toString();
        assertTrue(destructive.contains("android_force_stop_app"));
        assertTrue(destructive.contains("android_file_delete"));
        assertFalse(destructive.contains("android_flow"));
    }
}
