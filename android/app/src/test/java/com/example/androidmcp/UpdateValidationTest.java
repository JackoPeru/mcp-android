package com.example.androidmcp;

import org.junit.Test;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class UpdateValidationTest {
    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String APK = "mcp-android-0.6.1-debug.apk";

    @Test public void checksumMustMatchExactAssetName() throws Exception {
        assertEquals(HASH, UpdateValidation.parseHash(HASH + "  " + APK, APK));
        assertEquals(HASH, UpdateValidation.parseHash(HASH.toUpperCase() + " *" + APK, APK));
        assertThrows(IOException.class,
                () -> UpdateValidation.parseHash(HASH + "  other.apk", APK));
        assertThrows(IOException.class,
                () -> UpdateValidation.parseHash("deadbeef  " + APK, APK));
    }

    @Test public void initialReleaseAssetsMustUseGithubHttps() throws Exception {
        String valid = "https://github.com/JackoPeru/mcp-android/releases/download/v0.6.1/" + APK;
        assertEquals(valid, UpdateValidation.requireDownloadUrl(valid));
        assertThrows(IOException.class,
                () -> UpdateValidation.requireDownloadUrl("http://github.com/JackoPeru/mcp-android/x.apk"));
        assertThrows(IOException.class,
                () -> UpdateValidation.requireDownloadUrl("https://github.com.evil.example/x.apk"));
        assertThrows(IOException.class,
                () -> UpdateValidation.requireDownloadUrl("https://release-assets.githubusercontent.com/x.apk"));
    }

    @Test public void downloadUrlIsPinnedToThisRepoReleasePath() throws Exception {
        String valid = "https://github.com/JackoPeru/mcp-android/releases/download/v0.8.3/mcp-android-0.8.3-debug.apk";
        assertEquals(valid, UpdateValidation.requireDownloadUrl(valid));
        assertThrows(IOException.class,
                () -> UpdateValidation.requireDownloadUrl("https://github.com/evil/mcp-android/releases/download/v0.8.3/mcp-android-0.8.3-debug.apk"));
        assertThrows(IOException.class,
                () -> UpdateValidation.requireDownloadUrl("https://github.com/JackoPeru/other/releases/download/v1.0.0/x.apk"));
        assertThrows(IOException.class,
                () -> UpdateValidation.requireDownloadUrl("https://github.com/JackoPeru/mcp-android/releases/download/v0.8.3/mcp-android-0.8.3-debug.apk?token=x"));
        assertThrows(IOException.class,
                () -> UpdateValidation.requireDownloadUrl("https://github.com/JackoPeru/mcp-android/archive/refs/heads/main.zip"));
    }

    @Test public void cachedArtifactMustMatchExactPublishedSha256() throws Exception {
        File file = File.createTempFile("mcp-update-", ".apk");
        try {
            Files.write(file.toPath(), "published bytes".getBytes(StandardCharsets.UTF_8));
            String digest = UpdateValidation.sha256(file);
            assertEquals(64, digest.length());
            assertEquals(digest, UpdateValidation.requireMatchingSha256(file, digest));
            assertThrows(IOException.class,
                    () -> UpdateValidation.requireMatchingSha256(file, "0".repeat(64)));
        } finally {
            file.delete();
        }
    }
}
