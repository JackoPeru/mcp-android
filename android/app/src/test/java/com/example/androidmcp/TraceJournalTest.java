package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TraceJournalTest {
    @Test public void keepsOnlyMetadataAndBoundsHistory() throws Exception {
        TraceJournal.clearForTests();
        for (int i = 0; i < 140; i++) {
            TraceJournal.add("flow_step", "set_text", i, i % 2 == 0 ? "ok" : "error",
                    i % 2 == 0 ? "" : "INPUT_REJECTED");
        }
        JSONObject recent = TraceJournal.recent(200);
        JSONArray entries = recent.getJSONArray("traces");
        assertEquals(128, entries.length());
        assertTrue(recent.getBoolean("truncated"));
        String encoded = recent.toString();
        assertFalse(encoded.contains("secret-password"));
        assertFalse(encoded.contains("params"));
        assertFalse(encoded.contains("shell"));
    }

    @Test public void capsAndSanitizesLabels() throws Exception {
        TraceJournal.clearForTests();
        TraceJournal.add("x".repeat(200), "y".repeat(300), 5, "ok", "e".repeat(300));
        JSONObject item = TraceJournal.recent(1).getJSONArray("traces").getJSONObject(0);
        assertTrue(item.getString("kind").length() <= 40);
        assertTrue(item.getString("operation").length() <= 80);
        assertTrue(item.getString("error").length() <= 80);
    }
}
