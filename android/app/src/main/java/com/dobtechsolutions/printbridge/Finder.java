package com.dobtechsolutions.printbridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.dobtechsolutions.printbridge.core.LanScanner;

import java.net.Inet4Address;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * "Find printers": looks on the WiFi network (port 9100), over Bluetooth (paired printers
 * plus a short scan for nearby ones) and on USB at the same time, and reports each printer
 * as it is found. Also pairs a nearby Bluetooth printer from inside the app.
 */
final class Finder {
    static final String KIND_LAN = "lan";
    static final String KIND_BT = "bt";
    static final String KIND_BT_NEW = "bt_new";
    static final String KIND_USB = "usb";

    static final class Item {
        String kind;
        String name;
        String ip = "";
        int port = 9100;
        String btAddress = "";
        String usbKey = "";
    }

    interface Callback {
        void onItem(Item item);

        /** Status line: what is being searched right now. */
        void onProgress(String text);

        void onDone(int found);
    }

    interface PairCallback {
        void onPaired(String address, String name);

        void onPairFailed(String name);
    }

    /** Names printers commonly use over Bluetooth (class is often missing or wrong). */
    private static final Pattern PRINTER_NAME = Pattern.compile(
            "(?i)(print|pos|tm-|tm_|tsp|tup|mpt|rpp|mtp|xp-|munbyn|star|epson|bixolon|sewoo|rongta|"
                    + "hprt|goojprt|zj-|receipt|thermal|58mm|80mm|innerprinter|sunmi)");

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final Set<String> seen = new HashSet<>();
    private Callback cb;
    private BroadcastReceiver btReceiver;
    private boolean lanDone, btDone;
    private int count;

    Finder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Starts all searches. {@code bluetoothScanAllowed}: permission for discovery was granted. */
    void start(Callback callback, boolean bluetoothAllowed, boolean bluetoothScanAllowed) {
        stop();
        cancel.set(false);
        seen.clear();
        count = 0;
        cb = callback;
        lanDone = false;
        btDone = false;

        findUsb();
        findBluetooth(bluetoothAllowed, bluetoothScanAllowed);
        findLan();
    }

    void stop() {
        cancel.set(true);
        stopBluetoothScan();
    }

    // ---- USB --------------------------------------------------------------------------------

    private void findUsb() {
        for (UsbDevice d : UsbPrinter.candidates(ctx)) {
            Item it = new Item();
            it.kind = KIND_USB;
            it.name = UsbPrinter.labelOf(d);
            it.usbKey = UsbPrinter.keyOf(d);
            emit("usb:" + it.usbKey, it);
        }
    }

    // ---- WiFi / network -----------------------------------------------------------------------

    private void findLan() {
        final String[] own = ownIpv4();
        if (own == null) {
            progress(ctx.getString(R.string.find_no_wifi));
            lanDone = true;
            maybeDone();
            return;
        }
        final List<String> hosts = LanScanner.hostsAround(own[0], Integer.parseInt(own[1]));
        progress(ctx.getString(R.string.find_searching_wifi, own[0]));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LanScanner.scan(hosts, 9100, 450, 48, new LanScanner.Listener() {
                        @Override
                        public void onFound(LanScanner.Found f) {
                            Item it = new Item();
                            it.kind = KIND_LAN;
                            it.name = f.model.isEmpty() ? ctx.getString(R.string.find_network_printer) : f.model;
                            it.ip = f.ip;
                            it.port = f.port;
                            emit("lan:" + f.ip, it);
                        }
                    }, cancel);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        lanDone = true;
                        maybeDone();
                    }
                });
            }
        }, "dts-lan-scan").start();
    }

    /** {ip, prefix} of the WiFi/Ethernet network this device is on, or null. */
    private String[] ownIpv4() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null) continue;
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                        return new String[]{la.getAddress().getHostAddress(), String.valueOf(la.getPrefixLength())};
                    }
                }
            }
        } catch (SecurityException ignored) {
        }
        return null;
    }

    // ---- Bluetooth ----------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void findBluetooth(boolean allowed, boolean scanAllowed) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (!allowed || adapter == null || !adapter.isEnabled()) {
            btDone = true;
            return;
        }
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (!looksLikePrinter(d)) continue;
            Item it = new Item();
            it.kind = KIND_BT;
            it.name = nameOf(d);
            it.btAddress = d.getAddress();
            emit("bt:" + d.getAddress(), it);
        }
        if (!scanAllowed) {
            btDone = true;
            return;
        }
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null || !looksLikePrinter(d)) return;
                    Item it = new Item();
                    it.kind = d.getBondState() == BluetoothDevice.BOND_BONDED ? KIND_BT : KIND_BT_NEW;
                    it.name = nameOf(d);
                    it.btAddress = d.getAddress();
                    emit("bt:" + d.getAddress(), it);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    stopBluetoothScan();
                    btDone = true;
                    maybeDone();
                }
            }
        };
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        register(btReceiver, f);
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            if (!adapter.startDiscovery()) {
                stopBluetoothScan();
                btDone = true;
            }
        } catch (SecurityException e) {
            stopBluetoothScan();
            btDone = true;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopBluetoothScan() {
        if (btReceiver != null) {
            try {
                ctx.unregisterReceiver(btReceiver);
            } catch (RuntimeException ignored) {
            }
            btReceiver = null;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    static boolean looksLikePrinter(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING) return true;
        String n = d.getName();
        return n != null && PRINTER_NAME.matcher(n).find();
    }

    @SuppressLint("MissingPermission")
    static String nameOf(BluetoothDevice d) {
        String n = d.getName();
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    /**
     * Pairs a nearby printer. Fills in PIN 0000 when the printer asks for one (Epson, most
     * clones); otherwise Android shows its own pairing prompt.
     */
    @SuppressLint("MissingPermission")
    void pair(final String address, final PairCallback pc) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            pc.onPairFailed(address);
            return;
        }
        stopBluetoothScan();
        final BluetoothDevice device = adapter.getRemoteDevice(address);
        final BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || !address.equals(d.getAddress())) return;
                if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                    int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                        try {
                            d.setPin("0000".getBytes());
                        } catch (RuntimeException ignored) {
                            // Android shows its own PIN prompt instead.
                        }
                    }
                    return;
                }
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                if (state == BluetoothDevice.BOND_BONDED) {
                    unregisterQuietly(this);
                    pc.onPaired(address, nameOf(d));
                } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                    unregisterQuietly(this);
                    pc.onPairFailed(nameOf(d));
                }
            }
        };
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        register(r, f);
        try {
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                unregisterQuietly(r);
                pc.onPaired(address, nameOf(device));
            } else if (!device.createBond()) {
                unregisterQuietly(r);
                pc.onPairFailed(nameOf(device));
            }
        } catch (SecurityException e) {
            unregisterQuietly(r);
            pc.onPairFailed(address);
        }
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        // System broadcasts: must be exported to receive them on Android 13+.
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(r, f);
    }

    private void unregisterQuietly(BroadcastReceiver r) {
        try {
            ctx.unregisterReceiver(r);
        } catch (RuntimeException ignored) {
        }
    }

    // ---- results ------------------------------------------------------------------------------

    private void emit(final String key, final Item it) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (cancel.get() || !seen.add(key)) return;
                count++;
                if (cb != null) cb.onItem(it);
            }
        });
    }

    private void progress(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (cb != null && !cancel.get()) cb.onProgress(text);
            }
        });
    }

    private void maybeDone() {
        // Posted so it always runs after the results that were queued before it.
        main.post(new Runnable() {
            @Override
            public void run() {
                if (lanDone && btDone && cb != null && !cancel.get()) {
                    Callback c = cb;
                    cb = null;
                    c.onDone(count);
                }
            }
        });
    }
}
