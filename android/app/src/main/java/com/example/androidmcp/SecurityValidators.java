package com.example.androidmcp;

import java.util.regex.Pattern;

/** Pure bounds and trust-boundary checks shared by HTTP and SAF handlers. */
public final class SecurityValidators {
    public static final int MAX_JSON_BYTES = 64 * 1024;
    public static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_FILE_READ_BYTES = 262_144;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_PATH_LENGTH = 1_024;
    public static final int MAX_TEXT_LENGTH = 4_096;
    public static final int MAX_PACKAGE_LENGTH = 200;
    public static final int MAX_SELECTOR_TEXT = 512;
    public static final int MAX_URI_LENGTH = 2_048;
    public static final int MAX_SHELL_INPUT = 32 * 1024;
    public static final int MAX_SEARCH_RESULTS = 500;
    public static final int MAX_FILE_WRITE_BYTES = 262_144;

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ROOT_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private SecurityValidators() { }

    public static boolean isBoundedJson(String value) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 16) return false; }
            else if (c == '}' || c == ']') { if (--depth < 0) return false; }
            else if (c == '\'' || c == '/') return false;
        }
        return !quoted && depth == 0;
    }

    public static boolean isValidToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    public static boolean isValidRootId(String value) {
        return value != null && ROOT_ID.matcher(value).matches();
    }

    public static boolean isValidRelativePath(String value) {
        if (value == null || value.length() > MAX_PATH_LENGTH || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0) {
            return false;
        }
        if (value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20) {
                return false;
            }
        }
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidReadRange(long offset, long length) {
        return offset >= 0 && length >= 0 && length <= MAX_FILE_READ_BYTES
                && offset <= Long.MAX_VALUE - length;
    }

    public static boolean isValidWriteRange(long offset, long length) {
        return offset >= 0 && length >= 0 && length <= MAX_FILE_WRITE_BYTES
                && offset <= Long.MAX_VALUE - length;
    }

    public static boolean isValidFileName(String value) {
        if (value == null || value.isEmpty() || value.length() > 255
                || value.equals(".") || value.equals("..")
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20) return false;
        }
        return true;
    }

    public static boolean isValidPage(long offset, int limit) {
        return offset >= 0 && offset <= 1_000_000L && limit > 0 && limit <= MAX_PAGE_SIZE;
    }

    public static boolean isValidPackageName(String value) {
        return value != null && value.length() <= MAX_PACKAGE_LENGTH && PACKAGE.matcher(value).matches();
    }

    public static boolean isTailscaleIpv4(String value) {
        if (value == null) {
            return false;
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        int first;
        int second;
        try {
            first = Integer.parseInt(octets[0]);
            second = Integer.parseInt(octets[1]);
            for (String octet : octets) {
                if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')) {
                    return false;
                }
                for (int i = 0; i < octet.length(); i++) {
                    if (!Character.isDigit(octet.charAt(i)) || octet.charAt(i) > '9') {
                        return false;
                    }
                }
                int valuePart = Integer.parseInt(octet);
                if (valuePart < 0 || valuePart > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return first == 100 && second >= 64 && second <= 127;
    }
}
