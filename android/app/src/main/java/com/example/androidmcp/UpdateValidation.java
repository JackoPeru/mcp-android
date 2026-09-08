package com.example.androidmcp;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** Pure-Java validation rules shared by the updater and JVM unit tests. */
public final class UpdateValidation {
    private UpdateValidation() { }

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
        return uri.toString();
    }
}
