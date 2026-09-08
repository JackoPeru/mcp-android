package com.example.androidmcp;

/** Small strict semantic-version helper for public release tags. */
public final class Versioning {
    private Versioning() { }

    public static int compare(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    public static String normalizeTag(String value) {
        if (value == null) throw new IllegalArgumentException("Missing version");
        String normalized = value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        parse(normalized);
        return normalized;
    }

    private static int[] parse(String value) {
        if (value == null || !value.matches("^[vV]?[0-9]+[.][0-9]+[.][0-9]+$")) {
            throw new IllegalArgumentException("Invalid semantic version");
        }
        String normalized = (value.startsWith("v") || value.startsWith("V"))
                ? value.substring(1) : value;
        String[] parts = normalized.split("[.]");
        int[] result = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                if (parts[i].length() > 1 && parts[i].startsWith("0")) {
                    throw new IllegalArgumentException("Invalid semantic version");
                }
                result[i] = Integer.parseInt(parts[i]);
                if (result[i] < 0) throw new IllegalArgumentException("Invalid semantic version");
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid semantic version", e);
        }
    }
}
