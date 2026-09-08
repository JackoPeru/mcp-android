package com.example.androidmcp;

import org.junit.Test;

import java.io.IOException;

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
}
