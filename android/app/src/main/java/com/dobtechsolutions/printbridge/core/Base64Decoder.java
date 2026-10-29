package com.dobtechsolutions.printbridge.core;

/**
 * Standard base64 decoder (RFC 4648). java.util.Base64 needs API 26 and android.util.Base64
 * does not exist on the JVM, so the core carries its own. Ignores whitespace, accepts
 * missing padding and the URL-safe alphabet.
 */
public final class Base64Decoder {
    private static final int[] MAP = new int[128];

    static {
        java.util.Arrays.fill(MAP, -1);
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int k = 0; k < a.length(); k++) MAP[a.charAt(k)] = k;
        MAP['-'] = 62;
        MAP['_'] = 63;
    }

    private Base64Decoder() {}

    public static byte[] decode(String in) {
        if (in == null) throw new IllegalArgumentException("missing payload");
        byte[] out = new byte[in.length() * 3 / 4 + 3];
        int n = 0, acc = 0, bits = 0;
        boolean padding = false;
        for (int k = 0; k < in.length(); k++) {
            char c = in.charAt(k);
            if (c == '=') { padding = true; continue; }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            if (padding) throw new IllegalArgumentException("data after padding");
            int v = c < 128 ? MAP[c] : -1;
            if (v < 0) throw new IllegalArgumentException("invalid base64 character");
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) (acc >> bits);
                acc &= (1 << bits) - 1;
            }
        }
        if (bits >= 6) throw new IllegalArgumentException("truncated base64");
        byte[] res = new byte[n];
        System.arraycopy(out, 0, res, 0, n);
        return res;
    }
}
