package com.example.androidmcp;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class AllFilesStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private AllFilesStore store;

    @Before public void setUp() throws Exception {
        store = new AllFilesStore(temporary.getRoot());
    }

    private static String codeOf(Exception failure) {
        return failure instanceof ApiException ? ((ApiException) failure).code : "NO_CODE:" + failure;
    }

    @Test public void unknownRootIsRejected() {
        Exception failure = assertThrows(ApiException.class, () -> store.list("root_x", "", 0, 10));
        assertEquals("UNKNOWN_ROOT", codeOf(failure));
    }

    @Test public void traversalEscapesAreBlocked() {
        for (String path : new String[]{"..", "../secret", "a/../../b", "/etc", "a\\b"}) {
            Exception failure = assertThrows(ApiException.class, () -> store.list(AllFilesStore.ROOT_ID, path, 0, 10));
            assertEquals("INVALID_ARGUMENT", codeOf(failure));
        }
    }

    @Test public void systemDirectoriesStayBlocked() throws Exception {
        assertTrue(new File(temporary.getRoot(), "Android/data").mkdirs());
        assertTrue(new File(temporary.getRoot(), "Android/obb").mkdirs());
        assertTrue(new File(temporary.getRoot(), "Android/media").mkdirs());
        for (String path : new String[]{"Android/data", "Android/data/com.x", "ANDROID/OBB", "Android/obb/x"}) {
            Exception failure = assertThrows(ApiException.class, () -> store.list(AllFilesStore.ROOT_ID, path, 0, 10));
            assertEquals("OPERATION_UNSUPPORTED", codeOf(failure));
        }
        // Sibling with a similar prefix is fine.
        assertTrue(new File(temporary.getRoot(), "Android/media").isDirectory());
        JSONObject listed = store.list(AllFilesStore.ROOT_ID, "Android/media", 0, 10);
        assertTrue(listed.getBoolean("eof"));
    }

    @Test public void rootItselfCannotBeDeleted() {
        Exception failure = assertThrows(ApiException.class,
                () -> store.delete(AllFilesStore.ROOT_ID, ""));
        assertEquals("INVALID_ARGUMENT", codeOf(failure));
    }

    @Test public void missingPathsReportNotFound() {
        assertEquals("NOT_FOUND", codeOf(assertThrows(ApiException.class,
                () -> store.stat(AllFilesStore.ROOT_ID, "nope.txt"))));
        assertEquals("NOT_FOUND", codeOf(assertThrows(ApiException.class,
                () -> store.read(AllFilesStore.ROOT_ID, "nope.txt", 0, 100))));
    }

    @Test public void fullRoundTripKeepsContractShapes() throws Exception {
        assertEquals(true, store.mkdir(AllFilesStore.ROOT_ID, "docs").getBoolean("ok"));
        String payload = java.util.Base64.getEncoder()
                .encodeToString("hello-shared".getBytes(StandardCharsets.UTF_8));
        JSONObject written = store.write(AllFilesStore.ROOT_ID, "docs/note.txt", payload, 0, true, "text/plain");
        assertTrue(written.getBoolean("created"));
        assertEquals(12, written.getInt("bytesWritten"));

        JSONObject listed = store.list(AllFilesStore.ROOT_ID, "docs", 0, 10);
        assertEquals(1, listed.getJSONArray("entries").length());
        assertTrue(listed.getBoolean("eof"));

        JSONObject stat = store.stat(AllFilesStore.ROOT_ID, "docs/note.txt");
        assertEquals(false, stat.getBoolean("isDirectory"));
        assertTrue(stat.getBoolean("writableRoot"));

        JSONObject read = store.read(AllFilesStore.ROOT_ID, "docs/note.txt", 0, 256);
        assertEquals("hello-shared", new String(
                java.util.Base64.getDecoder().decode(read.getString("data")), StandardCharsets.UTF_8));
        assertTrue(read.getBoolean("eof"));

        JSONObject found = store.search(AllFilesStore.ROOT_ID, "", "note", 4, 10);
        assertEquals(1, found.getJSONArray("matches").length());
        assertEquals("docs/note.txt", found.getJSONArray("matches").getJSONObject(0).getString("path"));

        assertEquals(true, store.rename(AllFilesStore.ROOT_ID, "docs/note.txt", "renamed.txt").getBoolean("ok"));
        assertEquals(true, store.move(AllFilesStore.ROOT_ID, "docs/renamed.txt", "").getBoolean("ok"));
        assertEquals(true, store.copy(AllFilesStore.ROOT_ID, "renamed.txt", "docs").getBoolean("ok"));
        assertEquals(true, store.delete(AllFilesStore.ROOT_ID, "renamed.txt").getBoolean("ok"));
        assertEquals(true, store.delete(AllFilesStore.ROOT_ID, "docs").getBoolean("ok"));
        assertEquals("NOT_FOUND", codeOf(assertThrows(ApiException.class,
                () -> store.stat(AllFilesStore.ROOT_ID, "docs"))));
    }

    @Test public void writeRangeRulesMirrorSaf() throws Exception {
        assertTrue(store.mkdir(AllFilesStore.ROOT_ID, "w").getBoolean("ok"));
        String payload = java.util.Base64.getEncoder().encodeToString("abc".getBytes(StandardCharsets.UTF_8));
        // truncate with nonzero offset is rejected, like SAF.
        assertEquals("INVALID_ARGUMENT", codeOf(assertThrows(ApiException.class,
                () -> store.write(AllFilesStore.ROOT_ID, "w/f.bin", payload, 3, true, "application/octet-stream"))));
        // missing parent is NOT_FOUND, parents are never auto-created.
        assertEquals("NOT_FOUND", codeOf(assertThrows(ApiException.class,
                () -> store.write(AllFilesStore.ROOT_ID, "ghost/f.bin", payload, 0, true, "application/octet-stream"))));
    }

    @Test public void paginationShapeMatchesSaf() throws Exception {
        assertTrue(store.mkdir(AllFilesStore.ROOT_ID, "p").getBoolean("ok"));
        String payload = java.util.Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        store.write(AllFilesStore.ROOT_ID, "p/a.txt", payload, 0, true, "text/plain");
        store.write(AllFilesStore.ROOT_ID, "p/b.txt", payload, 0, true, "text/plain");
        store.write(AllFilesStore.ROOT_ID, "p/c.txt", payload, 0, true, "text/plain");
        JSONObject first = store.list(AllFilesStore.ROOT_ID, "p", 0, 2);
        assertEquals(2, first.getJSONArray("entries").length());
        assertFalse(first.getBoolean("eof"));
        assertEquals(2, first.getInt("nextOffset"));
        JSONObject second = store.list(AllFilesStore.ROOT_ID, "p", 2, 2);
        assertEquals(1, second.getJSONArray("entries").length());
        assertTrue(second.getBoolean("eof"));
        JSONObject root = store.list(AllFilesStore.ROOT_ID, "", 0, 10);
        assertEquals("p", root.getJSONArray("entries").getJSONObject(0).getString("name"));
    }
}
