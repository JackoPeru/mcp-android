package com.example.androidmcp;

/** Concurrency policy for RPC methods. Keeps long waits/shells from blocking UI gestures. */
public final class RpcPolicy {
    public enum LockDomain { NONE, UI, FILE, SHELL }

    private RpcPolicy() { }

    public static LockDomain lockDomain(String method) {
        if (method == null) return LockDomain.UI;
        if (method.startsWith("file_")) return LockDomain.FILE;
        switch (method) {
            case "status":
            case "screen_context":
            case "screen_diff":
            case "wait_idle":
            case "wait_change":
            case "wait_activity":
            case "device_info":
            case "events":
            case "events_wait":
            case "shell_status":
            case "shizuku_status":
            case "privileged_status":
                return LockDomain.NONE;
            case "shell":
            case "shizuku_shell":
                return LockDomain.SHELL;
            case "scroll_to":
                return LockDomain.UI;
            default:
                return LockDomain.UI;
        }
    }
}
