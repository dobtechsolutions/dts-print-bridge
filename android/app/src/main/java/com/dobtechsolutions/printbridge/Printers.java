package com.dobtechsolutions.printbridge;

import android.content.Context;

import com.dobtechsolutions.printbridge.core.BridgeClient;
import com.dobtechsolutions.printbridge.core.TcpPrinter;

/**
 * Builds the printer for the configured mode. The Bluetooth printer is cached so the service
 * and the Test print button share one open RFCOMM socket instead of fighting over it.
 */
public final class Printers {
    private static BluetoothPrinter bt;

    private Printers() {}

    public static synchronized BridgeClient.Printer forConfig(Context ctx, Config c) {
        if (Config.MODE_BLUETOOTH.equals(c.mode)) {
            if (bt == null || !bt.getAddress().equals(c.btAddress)) {
                if (bt != null) bt.close();
                bt = new BluetoothPrinter(c.btAddress);
            }
            return bt;
        }
        if (Config.MODE_USB.equals(c.mode)) return new UsbPrinter(ctx, c.usbKey);
        return new TcpPrinter(c.ip, c.port);
    }

    public static synchronized void closeBluetooth() {
        if (bt != null) bt.close();
        bt = null;
    }
}
