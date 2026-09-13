package com.example.androidmcp;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ShizukuShellServiceTest {
    @Test public void parentWorkdirSegmentsAreRejected() {
        assertTrue(ShizukuShellService.hasParentSegment("../etc"));
        assertTrue(ShizukuShellService.hasParentSegment("a/../../b"));
        assertTrue(ShizukuShellService.hasParentSegment("/data/local/tmp/../etc"));
        assertFalse(ShizukuShellService.hasParentSegment(""));
        assertFalse(ShizukuShellService.hasParentSegment("/data/local/tmp"));
        assertFalse(ShizukuShellService.hasParentSegment("subdir"));
        assertFalse(TermuxBridge.hasParentSegment("/data/local/tmp"));
        assertTrue(TermuxBridge.hasParentSegment(".."));
    }

    @Test public void unfinishedStdinCannotOutliveProcessDeadline() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java, "-version").start();
        CompletableFuture<Void> blockedStdin = new CompletableFuture<>();

        assertFalse(ShizukuShellService.awaitProcessAndStdin(
                process, blockedStdin, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
        process.destroyForcibly();
    }
}
