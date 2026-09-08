package com.example.androidmcp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ActionRegistryTest {
    @Test public void allowsUiActionsUsedByCompositeLoop() {
        assertTrue(ActionRegistry.isAllowed("ui_click"));
        assertTrue(ActionRegistry.isAllowed("ui_set_text"));
        assertTrue(ActionRegistry.isAllowed("double_tap"));
        assertTrue(ActionRegistry.isAllowed("drag"));
        assertTrue(ActionRegistry.isAllowed("launch_app"));
    }

    @Test public void rejectsShellFilesAndCompositeRecursion() {
        assertFalse(ActionRegistry.isAllowed("shell"));
        assertFalse(ActionRegistry.isAllowed("shizuku_shell"));
        assertFalse(ActionRegistry.isAllowed("file_delete"));
        assertFalse(ActionRegistry.isAllowed("act_and_observe"));
        assertFalse(ActionRegistry.isAllowed("flow"));
    }
}
