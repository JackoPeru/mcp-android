package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class VersioningTest {
    @Test public void comparesStrictSemanticVersions() {
        assertEquals(0, Versioning.compare("0.6.0", "v0.6.0"));
        assertTrue(Versioning.compare("0.6.1", "0.6.0") > 0);
        assertTrue(Versioning.compare("1.0.0", "0.99.99") > 0);
        assertTrue(Versioning.compare("0.5.9", "0.6.0") < 0);
    }

    @Test public void rejectsAmbiguousTags() {
        assertThrows(IllegalArgumentException.class, () -> Versioning.normalizeTag("v0.6"));
        assertThrows(IllegalArgumentException.class, () -> Versioning.normalizeTag("0.06.0"));
        assertThrows(IllegalArgumentException.class, () -> Versioning.normalizeTag("latest"));
    }
}
