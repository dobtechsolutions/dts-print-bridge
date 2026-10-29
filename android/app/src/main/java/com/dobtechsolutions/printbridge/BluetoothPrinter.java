package com.dobtechsolutions.printbridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import com.dobtechsolutions.printbridge.core.BridgeClient;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Bluetooth Classic (SPP/RFCOMM) printer. The socket stays open between jobs; on a write
 * failure it reconnects once and retries. Callers must hold BLUETOOTH_CONNECT on Android 12+.
 */
@SuppressLint("MissingPermission")
public final class BluetoothPrinter implements BridgeClient.Printer {
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final String address;
    private BluetoothSocket socket;

    public BluetoothPrinter(String address) {
        this.address = address;
    }

    public String getAddress() {
        return address;
    }

    @Override
    public synchronized void print(byte[] data, String jobAddress, int jobPort) throws IOException {
        try {
            write(data);
        } catch (IOException first) {
            BridgeState.log("bluetooth: " + first.getMessage() + " - reconnecting");
            close();
            write(data);
        }
    }

    private void write(byte[] data) throws IOException {
        if (socket == null || !socket.isConnected()) connect();
        OutputStream os = socket.getOutputStream();
        os.write(data);
        os.flush();
    }

    private void connect() throws IOException {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IOException("no Bluetooth on this device");
        if (!adapter.isEnabled()) throw new IOException("Bluetooth is off");
        if (address == null || address.isEmpty()) throw new IOException("no Bluetooth printer selected");
        BluetoothDevice device = adapter.getRemoteDevice(address);
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            BluetoothSocket s = null;
            try {
                if (attempt == 0) {
                    s = device.createRfcommSocketToServiceRecord(SPP);
                } else if (attempt == 1) {
                    s = device.createInsecureRfcommSocketToServiceRecord(SPP);
                } else {
                    // Fallback for printers with a broken SDP record: RFCOMM channel 1.
                    Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(device, 1);
                }
                s.connect();
                socket = s;
                BridgeState.log("bluetooth connected to " + address);
                return;
            } catch (IOException e) {
                last = e;
                closeQuietly(s);
            } catch (ReflectiveOperationException e) {
                last = new IOException(e.getMessage());
                closeQuietly(s);
            }
        }
        throw new IOException("cannot connect to " + address + ": " + (last == null ? "" : last.getMessage()));
    }

    public synchronized void close() {
        closeQuietly(socket);
        socket = null;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
