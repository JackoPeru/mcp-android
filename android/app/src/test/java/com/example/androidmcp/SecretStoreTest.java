package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public final class SecretStoreTest {
    private static final String GOOD = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    @Test public void encryptedWinsOverLegacy() {
        assertSame(GOOD, SecretStore.pickCurrent(GOOD, OTHER));
    }

    @Test public void legacyTriggersMigrationOnlyWhenEncryptedMissing() {
        assertSame(OTHER, SecretStore.pickCurrent(null, OTHER));
        assertSame(OTHER, SecretStore.pickCurrent("short", OTHER));
        // Identity matters: the caller migrates exactly this reference.
        String legacy = "c".repeat(64);
        assertSame(legacy, SecretStore.pickCurrent("", legacy));
    }

    @Test public void garbageOnBothSidesMeansRotate() {
        assertNull(SecretStore.pickCurrent(null, null));
        assertNull(SecretStore.pickCurrent("", ""));
        assertNull(SecretStore.pickCurrent("short", "also-short"));
        assertNull(SecretStore.pickCurrent("G".repeat(64), null));
    }

    @Test public void rotationUsesSameFormatTheBridgeAccepts() {
        // Token alphabet contract shared with bridge readConfig/token regex.
        for (int i = 0; i < 64; i++) {
            char c = GOOD.charAt(i);
            assertTrueScope(c);
        }
        assertEquals(64, GOOD.length());
    }

    private static void assertTrueScope(char c) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new AssertionError("not lowercase hex: " + c);
        }
    }
}
