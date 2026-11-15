package com.example.androidmcp;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * App-private store for captures (photos, audio, screen videos).
 *
 * <p>Separate from SAF/AllFiles roots on purpose: captures never live in
 * shared storage and cannot be reached by file_* RPCs, so an agent can only
 * read the media it created or the user listed. Filenames are generated here
 * and validated on read, which keeps traversal impossible without relying on
 * the provider. Files older than the retention window (24h) or beyond the
 * per-kind cap are deleted automatically on the next capture/status call.
 */
public final class CaptureStore {
    public static final String KINDS = "photo|audio|screen";
    private static final Pattern NAME = Pattern.compile(
            "^(" + KINDS + ")-[0-9]{13}-[a-z0-9]{6}\\.(jpg|png|m4a|ogg|mp4)$");
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_FILES_PER_KIND = 40;
    private static final int MAX_READ_BYTES = 262_144;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CaptureStore() { }

    public static File dir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "captures");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            // Fail loudly: a silent unwritable dir would fake a capture result.
            throw new IllegalStateException("capture storage unavailable");
        }
        return dir;
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static File newFile(Context context, String kind, String ext) {
        if (!Arrays.asList("photo", "audio", "screen").contains(kind)) {
            throw new IllegalArgumentException("Invalid capture kind");
        }
        if (!Arrays.asList("jpg", "png", "m4a", "ogg", "mp4").contains(ext)) {
            throw new IllegalArgumentException("Invalid capture extension");
        }
        byte[] random = new byte[3];
        RANDOM.nextBytes(random);
        StringBuilder suffix = new StringBuilder();
        for (byte b : random) suffix.append(Character.forDigit((b >> 4) & 0xf, 36));
        for (byte b : random) suffix.append(Character.forDigit(b & 0xf, 36));
        String name = kind + "-" + System.currentTimeMillis() + "-"
                + suffix.toString().substring(0, 6).toLowerCase(Locale.ROOT) + "." + ext;
        File file = new File(dir(context), name);
        if (!file.getParentFile().equals(dir(context))) {
            throw new IllegalStateException("capture path escapes store");
        }
        return file;
    }

    public static File resolve(Context context, String name) throws ApiException {
        if (!isValidName(name)) throw new ApiException("INVALID_ARGUMENT", "Invalid capture name");
        File file = new File(dir(context), name);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(dir(context).getCanonicalFile())) {
                throw new ApiException("INVALID_ARGUMENT", "Invalid capture name");
            }
        } catch (IOException e) {
            throw new ApiException("FILE_IO", "Unable to resolve capture");
        }
        if (!file.isFile()) throw new ApiException("NOT_FOUND", "Capture not found");
        return file;
    }

    public static String mimeType(String name) {
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    public static JSONObject list(Context context, String kind, int limit) throws ApiException {
        cleanup(context);
        String filter = kind == null || kind.isEmpty() ? "" : kind;
        if (!filter.isEmpty() && !Arrays.asList("photo", "audio", "screen").contains(filter)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid capture kind");
        }
        File[] files = dir(context).listFiles();
        if (files == null) throw new ApiException("FILE_IO", "Unable to list captures");
        List<File> matching = new ArrayList<>();
        for (File file : files) {
            if (!isValidName(file.getName())) continue;
            if (!filter.isEmpty() && !file.getName().startsWith(filter + "-")) continue;
            matching.add(file);
        }
        matching.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        JSONArray entries = new JSONArray();
        int max = Math.max(1, Math.min(limit, 200));
        try {
            for (int i = 0; i < matching.size() && i < max; i++) {
                File file = matching.get(i);
                entries.put(new JSONObject()
                        .put("name", file.getName())
                        .put("kind", file.getName().substring(0, file.getName().indexOf('-')))
                        .put("mimeType", mimeType(file.getName()))
                        .put("size", file.length())
                        .put("modified", file.lastModified()));
            }
            return new JSONObject()
                    .put("captures", entries)
                    .put("count", entries.length())
                    .put("total", matching.size())
                    .put("retentionMs", MAX_AGE_MS);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode captures");
        }
    }

    public static JSONObject read(Context context, String name, long offset, int length) throws ApiException {
        File file = resolve(context, name);
        if (offset < 0 || length < 1 || length > MAX_READ_BYTES) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid read range");
        }
        byte[] buffer = new byte[(int) Math.min(length, Math.max(0, file.length() - offset))];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(offset);
            int read = input.read(buffer);
            if (read < 0) read = 0;
            JSONObject result = new JSONObject();
            result.put("name", name);
            result.put("mimeType", mimeType(name));
            result.put("size", file.length());
            result.put("offset", offset);
            result.put("bytesRead", read);
            result.put("eof", offset + read >= file.length());
            result.put("data", java.util.Base64.getEncoder().encodeToString(
                    read == buffer.length ? buffer : Arrays.copyOf(buffer, read)));
            return result;
        } catch (IOException e) {
            throw new ApiException("FILE_IO", "Unable to read capture");
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode capture");
        }
    }

    public static JSONObject delete(Context context, String name) throws ApiException {
        File file = resolve(context, name);
        if (!file.delete()) throw new ApiException("FILE_IO", "Unable to delete capture");
        try {
            return new JSONObject().put("ok", true).put("name", name);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode result");
        }
    }

    /** Deletes stale captures. Never throws: cleanup must not break a capture. */
    public static synchronized void cleanup(Context context) {
        try {
            File[] files = dir(context).listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (String kind : new String[]{"photo", "audio", "screen"}) {
                List<File> kindFiles = new ArrayList<>();
                for (File file : files) {
                    if (file.isFile() && file.getName().startsWith(kind + "-") && isValidName(file.getName())) {
                        kindFiles.add(file);
                    }
                }
                kindFiles.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = 0; i < kindFiles.size(); i++) {
                    File file = kindFiles.get(i);
                    if (i >= MAX_FILES_PER_KIND || now - file.lastModified() > MAX_AGE_MS) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
        } catch (RuntimeException ignored) { }
    }

    /** Large captures are transferred in 256 KiB blocks by the agent. */
    public static int maxReadBytes() { return MAX_READ_BYTES; }
}
