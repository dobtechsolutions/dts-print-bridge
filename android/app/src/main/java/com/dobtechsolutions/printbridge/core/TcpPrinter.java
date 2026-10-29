package com.dobtechsolutions.printbridge.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * WiFi/LAN printer: raw TCP (JetDirect, usually port 9100). Uses the IP/port configured
 * in the app; falls back to printerAddress/printerPort from the job when the app IP is empty.
 */
public final class TcpPrinter implements BridgeClient.Printer {
    public static final int DEFAULT_PORT = 9100;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int WRITE_TIMEOUT_MS = 10000;

    private final String ip;
    private final int port;

    public TcpPrinter(String ip, int port) {
        this.ip = ip == null ? "" : ip.trim();
        this.port = port;
    }

    @Override
    public void print(byte[] data, String jobAddress, int jobPort) throws IOException {
        String host;
        int p;
        if (!ip.isEmpty()) {
            host = ip;
            p = port > 0 ? port : DEFAULT_PORT;
        } else {
            host = jobAddress == null ? "" : jobAddress.trim();
            p = jobPort > 0 ? jobPort : DEFAULT_PORT;
        }
        if (host.isEmpty()) throw new IOException("no printer IP configured");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, p), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(WRITE_TIMEOUT_MS);
            OutputStream os = socket.getOutputStream();
            os.write(data);
            os.flush();
            try {
                socket.shutdownOutput();
            } catch (IOException ignored) {
                // some printers reset early; data is already flushed
            }
        } catch (IOException e) {
            throw new IOException("connect/write " + host + ":" + p + ": " + e.getMessage(), e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
