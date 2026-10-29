package com.dobtechsolutions.printbridge;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.dobtechsolutions.printbridge.core.BridgeClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB printer via the USB host API: bulk OUT endpoint of a printer-class interface (class 7),
 * falling back to any interface with a bulk OUT endpoint (many cheap printers use vendor class).
 * Permission must have been granted from the settings screen (see MainActivity).
 */
public final class UsbPrinter implements BridgeClient.Printer {
    private static final int TIMEOUT_MS = 5000;
    private static final int CHUNK = 16384;

    private final Context ctx;
    private final String key;

    public UsbPrinter(Context ctx, String key) {
        this.ctx = ctx.getApplicationContext();
        this.key = key;
    }

    public static String keyOf(UsbDevice d) {
        return String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId());
    }

    public static String labelOf(UsbDevice d) {
        String name = null;
        try {
            name = d.getProductName();
        } catch (SecurityException ignored) {
        }
        if (name == null || name.isEmpty()) name = d.getDeviceName();
        return name + " (" + keyOf(d) + ")";
    }

    /** USB devices that expose a bulk OUT endpoint. */
    public static List<UsbDevice> candidates(Context ctx) {
        List<UsbDevice> out = new ArrayList<>();
        UsbManager m = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (m == null) return out;
        for (UsbDevice d : m.getDeviceList().values()) {
            if (findInterface(d) != null) out.add(d);
        }
        return out;
    }

    public static UsbDevice find(Context ctx, String key) {
        if (key == null || key.isEmpty()) return null;
        for (UsbDevice d : candidates(ctx)) if (keyOf(d).equals(key)) return d;
        return null;
    }

    private static UsbInterface findInterface(UsbDevice d) {
        UsbInterface fallback = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (bulkOut(intf) == null) continue;
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) return intf;
            if (fallback == null) fallback = intf;
        }
        return fallback;
    }

    private static UsbEndpoint bulkOut(UsbInterface intf) {
        for (int e = 0; e < intf.getEndpointCount(); e++) {
            UsbEndpoint ep = intf.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) return ep;
        }
        return null;
    }

    @Override
    public synchronized void print(byte[] data, String jobAddress, int jobPort) throws IOException {
        UsbManager m = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        UsbDevice d = find(ctx, key);
        if (m == null || d == null) throw new IOException("USB printer " + key + " not connected");
        if (!m.hasPermission(d)) throw new IOException("no USB permission - open the app and press Test print");
        UsbInterface intf = findInterface(d);
        UsbEndpoint ep = intf == null ? null : bulkOut(intf);
        if (ep == null) throw new IOException("no bulk OUT endpoint");
        UsbDeviceConnection conn = m.openDevice(d);
        if (conn == null) throw new IOException("cannot open USB device");
        try {
            if (!conn.claimInterface(intf, true)) throw new IOException("cannot claim USB interface");
            int off = 0;
            while (off < data.length) {
                int len = Math.min(CHUNK, data.length - off);
                byte[] chunk = new byte[len];
                System.arraycopy(data, off, chunk, 0, len);
                int n = conn.bulkTransfer(ep, chunk, len, TIMEOUT_MS);
                if (n <= 0) throw new IOException("USB write failed");
                off += n;
            }
            conn.releaseInterface(intf);
        } finally {
            conn.close();
        }
    }
}
