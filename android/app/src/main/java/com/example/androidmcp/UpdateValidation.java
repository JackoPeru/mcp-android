package com.example.androidmcp;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Pure-Java validation rules shared by the updater and JVM unit tests. */
public final class UpdateValidation {
    private UpdateValidation() { }

    static void configureRequest(HttpURLConnection connection, String accept) {
        if (accept != null && !accept.isEmpty()) connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "MCP-Android-Updater");
    }

    public static String parseHash(String sidecar, String apkName) throws IOException {
        String line = sidecar.trim();
        if (line.length() > 512) throw new IOException("Invalid checksum file");
        String[] parts = line.split("\\s+", 2);
        if (parts.length != 2 || !parts[0].matches("^[a-fA-F0-9]{64}$")) {
            throw new IOException("Invalid checksum file");
        }
        String named = parts[1].trim();
        if (named.startsWith("*")) named = named.substring(1);
        if (!apkName.equals(named)) throw new IOException("Checksum filename mismatch");
        return parts[0].toLowerCase(Locale.ROOT);
    }

    public static String requireDownloadUrl(String raw) throws Exception {
        URI uri = new URI(raw);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Invalid release URL");
        }
        // Pin to this repo's release downloads. Asset-name checks alone would
        // already reject a foreign file, but a tight prefix removes any
        // confusion between repos/orgs that happen to publish same-named APKs.
        // Redirect targets are still gated separately by UpdateManager's
        // DOWNLOAD_HOSTS allowlist + signer-equality verification.
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String prefix = "/JackoPeru/mcp-android/releases/download/";
        if (path.length() <= prefix.length()
                || !path.regionMatches(true, 0, prefix, 0, prefix.length())
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IOException("Invalid release URL");
        }
        return uri.toString();
    }

    public static String sha256(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Update file missing");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    public static String requireMatchingSha256(File file, String expected) throws IOException {
        if (expected == null || !expected.matches("^[a-f0-9]{64}$")) {
            throw new IOException("Invalid expected checksum");
        }
        String actual = sha256(file);
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("SHA-256 non valido");
        }
        return actual;
    }
}
