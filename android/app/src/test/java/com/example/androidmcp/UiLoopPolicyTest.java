package com.example.androidmcp;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UiLoopPolicyTest {
    @Test public void idleRequiresQuietEventsAndStableSemanticHash() {
        assertTrue(UiLoopEngine.isIdle(500, 250, "same", "same"));
        assertFalse(UiLoopEngine.isIdle(100, 250, "same", "same"));
        assertFalse(UiLoopEngine.isIdle(500, 250, "before", "after"));
    }

    @Test public void scrollStopsAtRepeatedStateOrStepLimit() {
        Set<String> seen = new HashSet<>();
        seen.add("a");
        assertTrue(UiLoopEngine.shouldStopScroll(seen, "a", 2, 8));
        assertTrue(UiLoopEngine.shouldStopScroll(seen, "b", 8, 8));
        assertFalse(UiLoopEngine.shouldStopScroll(seen, "b", 2, 8));
    }
}
