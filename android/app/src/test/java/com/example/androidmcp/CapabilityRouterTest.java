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

    @Test public void logRedactionRemovesGithubSlackGoogleAndPrivateKeys() {
        String input = "token ghp_abcdefghijklmnopqrstuvwxyz1234\n"
                + "slack xoxb-123456789012-ABCDEFGHIJ\n"
                + "gmaps AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ123456\n"
                + "-----BEGIN PRIVATE KEY-----\nMIIBvTBX\n-----END PRIVATE KEY-----";
        String redacted = CapabilityRouter.redactLogText(input);
        assertFalse(redacted.contains("ghp_"));
        assertFalse(redacted.contains("xoxb-"));
        assertFalse(redacted.contains("AIza"));
        assertFalse(redacted.contains("MIIBvTBX"));
    }

    @Test public void privilegedPackageValidationInvariant() {
        assertTrue(SecurityValidators.isValidPackageName("com.termux"));
        assertTrue(SecurityValidators.isValidPackageName("moe.shizuku.privileged.api"));
        assertFalse(SecurityValidators.isValidPackageName(""));
        assertFalse(SecurityValidators.isValidPackageName("../evil"));
        assertFalse(SecurityValidators.isValidPackageName("com.example;rm -rf"));
    }

    @Test public void logTagValidationInvariant() {
        assertTrue(CapabilityRouter.LOGCAT_TAG.matcher("ActivityManager").matches());
        assertTrue(CapabilityRouter.LOGCAT_TAG.matcher("a".repeat(80)).matches());
        assertFalse(CapabilityRouter.LOGCAT_TAG.matcher("").matches());
        assertFalse(CapabilityRouter.LOGCAT_TAG.matcher("a".repeat(81)).matches());
        assertFalse(CapabilityRouter.LOGCAT_TAG.matcher("tag with spaces").matches());
        assertFalse(CapabilityRouter.LOGCAT_TAG.matcher("tag' OR '1'='1").matches());
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
