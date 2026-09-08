package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ScreenSnapshotStoreTest {
    private static JSONObject context(String text, boolean focused) throws Exception {
        JSONObject node = new JSONObject()
                .put("path", "0/0")
                .put("text", text)
                .put("clickable", true)
                .put("focused", focused);
        return new JSONObject()
                .put("packageName", "com.example.test")
                .put("windowClass", "android.widget.FrameLayout")
                .put("input", new JSONObject().put("keyboardVisible", focused).put("focusedEditable", focused))
                .put("nodes", new JSONArray().put(node))
                .put("truncated", false);
    }

    @Test public void canonicalHashIsStableForEquivalentObjects() throws Exception {
        ScreenSnapshotStore store = new ScreenSnapshotStore();
        ScreenSnapshotStore.Snapshot first = store.capture(context("Continue", false));

        JSONObject reordered = new JSONObject()
                .put("truncated", false)
                .put("nodes", new JSONArray().put(new JSONObject()
                        .put("focused", false)
                        .put("clickable", true)
                        .put("text", "Continue")
                        .put("path", "0/0")))
                .put("input", new JSONObject().put("focusedEditable", false).put("keyboardVisible", false))
                .put("windowClass", "android.widget.FrameLayout")
                .put("packageName", "com.example.test");
        ScreenSnapshotStore.Snapshot second = store.capture(reordered);

        assertEquals(first.uiHash, second.uiHash);
    }

    @Test public void diffReportsSemanticNodeAndFocusChanges() throws Exception {
        ScreenSnapshotStore store = new ScreenSnapshotStore();
        ScreenSnapshotStore.Snapshot before = store.capture(context("Continue", false));
        ScreenSnapshotStore.Snapshot after = store.capture(context("Done", true));
        JSONObject diff = store.diff(before.id, after.id);

        assertTrue(diff.getBoolean("changed"));
        assertEquals(1, diff.getJSONArray("changedNodes").length());
        assertTrue(diff.getBoolean("focusChanged"));
        assertTrue(diff.getBoolean("keyboardChanged"));
        assertFalse(diff.getBoolean("packageChanged"));
    }

    @Test public void cacheKeepsOnlyEightSnapshots() throws Exception {
        ScreenSnapshotStore store = new ScreenSnapshotStore();
        long first = 0;
        long last = 0;
        for (int i = 0; i < 9; i++) {
            ScreenSnapshotStore.Snapshot snapshot = store.capture(context("n" + i, false));
            if (i == 0) first = snapshot.id;
            last = snapshot.id;
        }
        long evicted = first;
        long retained = last;
        assertThrows(ApiException.class, () -> store.diff(evicted, retained));
        assertEquals(retained, store.latestId());
    }
}
