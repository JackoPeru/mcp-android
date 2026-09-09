package com.example.androidmcp;

public final class NetworkAddressPolicy {
    private NetworkAddressPolicy() { }

    private static long ipv4(String ip) {
        if (ip == null) throw new IllegalArgumentException("IPv4 required");
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4");
        long value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) throw new IllegalArgumentException("Invalid IPv4");
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') throw new IllegalArgumentException("Invalid IPv4");
            }
            int octet;
            try { octet = Integer.parseInt(part); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid IPv4", e); }
            if (octet < 0 || octet > 255) throw new IllegalArgumentException("Invalid IPv4");
            value = (value << 8) | octet;
        }
        return value;
    }

    public static boolean isRfc1918(String ip) {
        long value = ipv4(ip);
        return (value & 0xff000000L) == 0x0a000000L
                || (value & 0xfff00000L) == 0xac100000L
                || (value & 0xffff0000L) == 0xc0a80000L;
    }

    public static boolean isTailscale(String ip) {
        return (ipv4(ip) & 0xffc00000L) == 0x64400000L;
    }

    public static boolean subnetContains(String ip, int prefixLength, String candidateIp) {
        if (prefixLength < 0 || prefixLength > 32) throw new IllegalArgumentException("Invalid prefix");
        long mask = prefixLength == 0 ? 0L : (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
        return (ipv4(ip) & mask) == (ipv4(candidateIp) & mask);
    }

    public static String broadcastAddress(String ip, int prefixLength) {
        if (prefixLength < 1 || prefixLength > 30) throw new IllegalArgumentException("Invalid LAN prefix");
        long mask = (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
        long value = (ipv4(ip) & mask) | (~mask & 0xffffffffL);
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "."
                + ((value >>> 8) & 255) + "." + (value & 255);
    }
}
