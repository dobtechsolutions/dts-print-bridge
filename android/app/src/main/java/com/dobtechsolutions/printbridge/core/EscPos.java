package com.dobtechsolutions.printbridge.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/** Local ESC/POS test ticket, printed directly from the app (no server involved). */
public final class EscPos {
    private EscPos() {}

    public static byte[] testTicket(String dateLine) {
        Charset ascii = Charset.forName("US-ASCII");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        put(b, 0x1B, 0x40);             // ESC @      initialise
        put(b, 0x1B, 0x61, 0x01);       // ESC a 1    centre
        put(b, 0x1B, 0x45, 0x01);       // ESC E 1    bold on
        put(b, 0x1D, 0x21, 0x11);       // GS ! 0x11  double width + height
        text(b, "TEST PRINT\n", ascii);
        put(b, 0x1D, 0x21, 0x00);       // GS ! 0     normal size
        put(b, 0x1B, 0x45, 0x00);       // ESC E 0    bold off
        text(b, "DTS Print Bridge works\n", ascii);
        text(b, "Android\n", ascii);
        text(b, dateLine + "\n", ascii);
        put(b, 0x1B, 0x61, 0x00);       // ESC a 0    left
        put(b, 0x1B, 0x64, 0x04);       // ESC d 4    feed 4 lines
        put(b, 0x1D, 0x56, 0x42, 0x00); // GS V B 0   feed + partial cut
        return b.toByteArray();
    }

    private static void put(ByteArrayOutputStream b, int... bytes) {
        for (int x : bytes) b.write(x);
    }

    private static void text(ByteArrayOutputStream b, String s, Charset cs) {
        byte[] t = s.getBytes(cs);
        b.write(t, 0, t.length);
    }
}
