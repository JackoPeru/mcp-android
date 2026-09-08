package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
}
