package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Direct shared-storage backend used only when {@link AllFilesAccess} is active.
 *
 * <p>Same RPC contract as {@link SafFileStore} (same method set, same JSON shapes,
 * same error codes) but over {@code java.io.File} instead of DocumentsProvider.
 * Root id is the fixed {@link #ROOT_ID}; any other id is {@code UNKNOWN_ROOT} so
 * callers cannot smuggle SAF ids in here or vice versa.
 *
 * <p>Intentionally dependency-free (no Android classes, no {@code RequestScope}):
 * local filesystem ops are bounded and fast, and this keeps the whole class
 * unit-testable on plain JVM. Base64 uses {@code java.util} for the same reason.
 */
public final class AllFilesStore {
    public static final String ROOT_ID = "all-files";
    public static final String DIRECTORY_MIME = "vnd.android.document/directory";
    private static final int MAX_DIR_ENTRIES = 100_000;
    private static final int MAX_SEARCH_SCAN = 10_000;

    private final File base;
    // 2s sorted-listing snapshot: sorting every page of a big directory is
    // O(n log n) per RPC; offset==0 always re-sorts, deeper pages reuse it.
    private volatile File[] sortedCache;
    private volatile String sortedCacheDir = "";
    private volatile long sortedCacheAt;

    public AllFilesStore(File baseDir) throws ApiException {
        if (baseDir == null) throw new ApiException("NOT_FOUND", "Shared storage unavailable");
        final File canonical;
        try {
            canonical = baseDir.getCanonicalFile();
        } catch (IOException e) {
            throw new ApiException("FILE_IO", "Shared storage unavailable");
        }
        if (!canonical.isDirectory()) throw new ApiException("NOT_FOUND", "Shared storage unavailable");
        this.base = canonical;
    }

    public JSONObject list(String rootId, String path, long offset, int limit) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        if (!SecurityValidators.isValidPage(offset, limit)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid page");
        }
        File directory = resolve(path);
        if (!directory.isDirectory()) {
            throw new ApiException("NOT_A_DIRECTORY", "Path is not a directory");
        }
        File[] kids = directory.listFiles();
        if (kids == null) throw new ApiException("FILE_IO", "Unable to list directory");
        if (kids.length > MAX_DIR_ENTRIES) {
            throw new ApiException("DIRECTORY_TOO_LARGE", "Directory traversal limit reached");
        }
        // Sort only for the first page or when the 2s snapshot is stale/for another dir.
        long nowMs = System.currentTimeMillis();
        File[] cached = sortedCache;
        if (offset != 0 && cached != null && directory.getPath().equals(sortedCacheDir)
                && nowMs - sortedCacheAt < 2_000) {
            kids = cached;
        } else {
            Arrays.sort(kids, Comparator.comparing((File file) -> file.getName().toLowerCase(Locale.ROOT)));
            sortedCache = kids;
            sortedCacheDir = directory.getPath();
            sortedCacheAt = nowMs;
        }
        JSONArray entries = new JSONArray();
        boolean hasMore = false;
        long index = 0;
        for (File kid : kids) {
            if (index++ < offset) continue;
            if (entries.length() >= limit) { hasMore = true; break; }
            entries.put(toJson(kid));
        }
        JSONObject result = new JSONObject();
        try {
            result.put("entries", entries);
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("eof", !hasMore);
            if (hasMore && offset <= Long.MAX_VALUE - entries.length()) {
                result.put("nextOffset", offset + entries.length());
            } else {
                result.put("nextOffset", JSONObject.NULL);
            }
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode file list");
        }
    }

    public JSONObject stat(String rootId, String path) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        File target = resolve(path);
        if (!target.exists()) throw new ApiException("NOT_FOUND", "Path not found");
        JSONObject result = toJson(target);
        try {
            result.put("writableRoot", target.canWrite());
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode file metadata");
        }
    }

    public JSONObject read(String rootId, String path, long offset, long length) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        if (!SecurityValidators.isValidReadRange(offset, length) || length < 1) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid read range");
        }
        File file = resolve(path);
        if (!file.exists()) throw new ApiException("NOT_FOUND", "File not found");
        if (file.isDirectory()) throw new ApiException("NOT_A_FILE", "Path is a directory");
        byte[] data = new byte[(int) length];
        int bytesRead = 0;
        boolean eof = false;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            if (offset >= size) {
                eof = true;
            } else {
                input.seek(offset);
                int count = input.read(data, 0, data.length);
                if (count < 0) {
                    eof = true;
                } else {
                    bytesRead = count;
                    eof = offset + count >= size;
                }
            }
        } catch (IOException e) {
            throw new ApiException("READ_FAILED", "File read failed");
        }
        JSONObject result = new JSONObject();
        try {
            result.put("data", java.util.Base64.getEncoder().encodeToString(
                    Arrays.copyOf(data, bytesRead)));
            result.put("bytesRead", bytesRead);
            result.put("eof", eof);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode file data");
        }
    }

    public JSONObject search(String rootId, String basePath, String query, int maxDepth, int limit)
            throws ApiException {
        requireRoot(rootId);
        requirePath(basePath);
        if (query == null || query.length() > 255 || maxDepth < 0 || maxDepth > 16
                || limit < 1 || limit > SecurityValidators.MAX_SEARCH_RESULTS) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid search");
        }
        File baseDir = resolve(basePath);
        if (!baseDir.isDirectory()) throw new ApiException("NOT_A_DIRECTORY", "Search base is not a directory");
        String needle = query.toLowerCase(Locale.ROOT);
        JSONArray matches = new JSONArray();
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        queue.add(new SearchNode(baseDir, basePath, 0));
        int scanned = 0;
        boolean truncated = false;
        while (!queue.isEmpty() && matches.length() < limit) {
            SearchNode parent = queue.removeFirst();
            if (parent.depth >= maxDepth) continue;
            File[] kids = parent.dir.listFiles();
            if (kids == null) continue;
            for (File kid : kids) {
                if (++scanned > MAX_SEARCH_SCAN) {
                    truncated = true;
                    queue.clear();
                    break;
                }
                String childPath = parent.path.isEmpty() ? kid.getName() : parent.path + "/" + kid.getName();
                if (SecurityValidators.containsIgnoreCase(kid.getName(), needle)) {
                    JSONObject entry = toJson(kid);
                    try { entry.put("path", childPath); }
                    catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode search"); }
                    matches.put(entry);
                    if (matches.length() >= limit) { truncated = true; break; }
                }
                if (kid.isDirectory() && parent.depth + 1 < maxDepth) {
                    queue.addLast(new SearchNode(kid, childPath, parent.depth + 1));
                }
            }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("matches", matches);
            result.put("scanned", scanned);
            result.put("truncated", truncated || !queue.isEmpty());
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode search");
        }
    }

    public JSONObject write(String rootId, String path, String base64, long offset,
                            boolean truncate, String mimeType) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        if (path.isEmpty() || base64 == null
                || base64.length() > SecurityValidators.MAX_FILE_WRITE_BASE64_CHARS
                || mimeType == null || mimeType.isEmpty() || mimeType.length() > 200) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid write");
        }
        final byte[] data;
        try { data = java.util.Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException e) { throw new ApiException("INVALID_ARGUMENT", "Invalid base64"); }
        if (!SecurityValidators.isValidWriteRange(offset, data.length) || (truncate && offset != 0)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid write range");
        }
        File file = resolve(path);
        boolean created = !file.exists();
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new ApiException("NOT_FOUND", "Parent directory not found");
        }
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            // TOCTOU re-check: resolve() ran before open, so re-canonicalize and
            // re-gate after open in case the path was swapped meanwhile.
            File recanonical = file.getCanonicalFile();
            if (!isInsideBase(recanonical)) {
                throw new ApiException("INVALID_ARGUMENT", "Path escapes shared storage");
            }
            File reparent = recanonical.getParentFile();
            if (reparent == null || !reparent.isDirectory()) {
                throw new ApiException("NOT_FOUND", "Parent directory not found");
            }
            if (truncate) output.setLength(0);
            output.seek(offset);
            output.write(data);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("WRITE_FAILED", "File write failed");
        }
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("created", created);
            result.put("bytesWritten", data.length);
            result.put("offset", offset);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode write result");
        }
    }

    public JSONObject mkdir(String rootId, String path) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        if (path.isEmpty()) throw new ApiException("INVALID_ARGUMENT", "Cannot create root");
        File directory = resolve(path);
        if (directory.exists()) throw new ApiException("ALREADY_EXISTS", "Path already exists");
        if (!directory.mkdir()) throw new ApiException("WRITE_FAILED", "Directory creation failed");
        return okResult();
    }

    public JSONObject rename(String rootId, String path, String newName) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        if (path.isEmpty() || !SecurityValidators.isValidFileName(newName)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid rename");
        }
        File source = resolve(path);
        if (!source.exists()) throw new ApiException("NOT_FOUND", "Path not found");
        File parent = source.getParentFile();
        File dest = new File(parent, newName);
        if (!isInsideBase(dest)) throw new ApiException("INVALID_ARGUMENT", "Invalid rename");
        if (dest.exists()) throw new ApiException("ALREADY_EXISTS", "Name already exists");
        if (!source.renameTo(dest)) throw new ApiException("WRITE_FAILED", "Rename failed");
        return okResult();
    }

    public JSONObject move(String rootId, String path, String targetDirectory) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        requirePath(targetDirectory);
        if (path.isEmpty() || targetDirectory.equals(path)
                || (!path.isEmpty() && targetDirectory.startsWith(path + "/"))) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid move target");
        }
        File source = resolve(path);
        if (!source.exists()) throw new ApiException("NOT_FOUND", "Path not found");
        File target = resolve(targetDirectory);
        if (!target.isDirectory()) throw new ApiException("NOT_A_DIRECTORY", "Move target is not a directory");
        File dest = new File(target, source.getName());
        if (dest.exists()) throw new ApiException("ALREADY_EXISTS", "Name already exists");
        if (!source.renameTo(dest)) throw new ApiException("WRITE_FAILED", "Move failed");
        return okResult();
    }

    public JSONObject copy(String rootId, String path, String targetDirectory) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        requirePath(targetDirectory);
        if (path.isEmpty()) throw new ApiException("INVALID_ARGUMENT", "Cannot copy root");
        File source = resolve(path);
        if (!source.exists()) throw new ApiException("NOT_FOUND", "Path not found");
        File target = resolve(targetDirectory);
        if (!target.isDirectory()) throw new ApiException("NOT_A_DIRECTORY", "Copy target is not a directory");
        File dest = new File(target, source.getName());
        if (dest.exists()) throw new ApiException("ALREADY_EXISTS", "Name already exists");
        int[] counter = {0};
        try {
            copyRecursive(source, dest, counter);
        } catch (IOException e) {
            throw new ApiException("WRITE_FAILED", "Copy failed");
        }
        return okResult();
    }

    public JSONObject delete(String rootId, String path) throws ApiException {
        requireRoot(rootId);
        requirePath(path);
        if (path.isEmpty()) throw new ApiException("INVALID_ARGUMENT", "Cannot delete authorized root");
        File source = resolve(path);
        if (!source.exists()) throw new ApiException("NOT_FOUND", "Path not found");
        int[] counter = {0};
        if (!deleteRecursive(source, counter)) throw new ApiException("WRITE_FAILED", "Delete failed");
        return okResult();
    }

    private File resolve(String path) throws ApiException {
        requirePath(path);
        final File target;
        try {
            target = new File(base, path).getCanonicalFile();
        } catch (IOException e) {
            throw new ApiException("FILE_IO", "Unable to resolve path");
        }
        if (!isInsideBase(target)) {
            throw new ApiException("INVALID_ARGUMENT", "Path escapes shared storage");
        }
        // Mirror the platform restriction with a clear code instead of a raw I/O error.
        String relative = relativePath(target);
        String head = firstTwoSegments(relative);
        if (head.equals("android/data") || head.equals("android/obb")) {
            throw new ApiException("OPERATION_UNSUPPORTED", "System directory is not accessible");
        }
        return target;
    }

    private boolean isInsideBase(File target) {
        String root = base.getPath();
        String candidate = target.getPath();
        return candidate.equals(root) || candidate.startsWith(root + File.separator);
    }

    private String relativePath(File target) {
        String root = base.getPath();
        String candidate = target.getPath();
        if (candidate.equals(root)) return "";
        // Normalize separators: File paths use '\' on Windows, '/' on Android.
        return candidate.substring(root.length() + 1).replace('\\', '/');
    }

    private static String firstTwoSegments(String relative) {
        String[] parts = relative.split("/", 3);
        String first = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "";
        String second = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";
        return second.isEmpty() ? first : first + "/" + second;
    }

    private void copyRecursive(File source, File dest, int[] counter) throws IOException {
        if (source.isDirectory()) {
            if (!dest.mkdir()) throw new IOException("mkdir failed: " + dest);
            File[] kids = source.listFiles();
            if (kids == null) throw new IOException("list failed: " + source);
            for (File kid : kids) {
                if (++counter[0] > MAX_SEARCH_SCAN) {
                    throw new IOException("copy limit reached");
                }
                copyRecursive(kid, new File(dest, kid.getName()), counter);
            }
            return;
        }
        if (++counter[0] > MAX_SEARCH_SCAN) throw new IOException("copy limit reached");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.flush();
        }
    }

    private static boolean deleteRecursive(File target, int[] counter) {
        if (++counter[0] > MAX_SEARCH_SCAN) return false;
        if (target.isDirectory()) {
            File[] kids = target.listFiles();
            if (kids == null) return false;
            for (File kid : kids) {
                if (!deleteRecursive(kid, counter)) return false;
            }
        }
        return target.delete();
    }

    private JSONObject toJson(File file) throws ApiException {
        JSONObject object = new JSONObject();
        try {
            object.put("name", file.getName());
            object.put("mimeType", file.isDirectory() ? DIRECTORY_MIME : guessMime(file.getName()));
            object.put("size", file.isDirectory() ? 0 : file.length());
            object.put("lastModified", file.lastModified());
            object.put("isDirectory", file.isDirectory());
            return object;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode file metadata");
        }
    }

    private static String guessMime(String name) {
        try {
            String guessed = URLConnection.guessContentTypeFromName(name);
            return guessed == null ? "" : guessed;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void requireRoot(String rootId) throws ApiException {
        if (!ROOT_ID.equals(rootId)) throw new ApiException("UNKNOWN_ROOT", "Unknown root");
    }

    private static void requirePath(String path) throws ApiException {
        if (!SecurityValidators.isValidRelativePath(path)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid relative path");
        }
    }

    private static JSONObject okResult() throws ApiException {
        JSONObject result = new JSONObject();
        try { result.put("ok", true); return result; }
        catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode result"); }
    }

    private static final class SearchNode {
        final File dir;
        final String path;
        final int depth;
        SearchNode(File dir, String path, int depth) {
            this.dir = dir; this.path = path; this.depth = depth;
        }
    }
}
