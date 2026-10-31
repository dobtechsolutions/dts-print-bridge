package com.dobtechsolutions.printbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.dobtechsolutions.printbridge.core.BridgeClient;
import com.dobtechsolutions.printbridge.core.EscPos;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Settings screen: token, printer mode, Start/Stop, Test print, status line and event log. */
public final class MainActivity extends Activity implements BridgeState.Listener {
    private static final String ACTION_USB_PERMISSION = "com.dobtechsolutions.printbridge.USB_PERMISSION";
    private static final int REQ_BT = 1;
    private static final int REQ_NOTIFY = 2;
    private static final int REQ_FIND = 3;

    private enum Pending { NONE, START, TEST, LIST_BT }

    private EditText token, ip, port;
    private TextView tokenSaved, status, log, btHelp, batteryHelp, findStatus;
    private LinearLayout findResults;
    private Button findButton;
    private Switch orderAlarm;
    private Finder finder;
    private RadioGroup mode;
    private View lanSection, btSection, usbSection;
    private Spinner btDevice, usbDevice;
    private Button startStop, battery;

    private final List<String[]> btDevices = new ArrayList<>();   // {address, label}
    private final List<String[]> usbDevices = new ArrayList<>();  // {key, label}
    private Config cfg;
    private Pending pending = Pending.NONE;
    private boolean usbReceiverRegistered;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            BridgeState.log("USB permission " + (granted ? "granted" : "denied"));
            if (granted) runPending();
            else {
                pending = Pending.NONE;
                toast(getString(R.string.usb_permission_denied));
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        token = findViewById(R.id.token);
        ip = findViewById(R.id.ip);
        port = findViewById(R.id.port);
        tokenSaved = findViewById(R.id.tokenSaved);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        btHelp = findViewById(R.id.btHelp);
        batteryHelp = findViewById(R.id.batteryHelp);
        mode = findViewById(R.id.mode);
        lanSection = findViewById(R.id.lanSection);
        btSection = findViewById(R.id.btSection);
        usbSection = findViewById(R.id.usbSection);
        btDevice = findViewById(R.id.btDevice);
        usbDevice = findViewById(R.id.usbDevice);
        startStop = findViewById(R.id.startStop);
        battery = findViewById(R.id.battery);
        findStatus = findViewById(R.id.findStatus);
        findResults = findViewById(R.id.findResults);
        findButton = findViewById(R.id.findPrinters);
        finder = new Finder(this);
        orderAlarm = findViewById(R.id.orderAlarm);
        orderAlarm.setChecked(Config.load(this).orderAlarm);
        orderAlarm.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                cfg.orderAlarm = on;
                cfg.save(MainActivity.this);
                BridgeState.log("order alarm " + (on ? "on" : "off"));
                if (BridgeState.isRunning()) toast(getString(R.string.restart_to_apply));
            }
        });
        findViewById(R.id.testAlarm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OrderAlarm.test(MainActivity.this);
            }
        });
        findButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestFindPermissionsThenSearch();
            }
        });

        cfg = Config.load(this);
        if (state == null) {
            token.setText(cfg.token);
            ip.setText(cfg.ip);
            port.setText(String.valueOf(cfg.port > 0 ? cfg.port : 9100));
        }
        mode.check(Config.MODE_BLUETOOTH.equals(cfg.mode) ? R.id.modeBt
                : Config.MODE_USB.equals(cfg.mode) ? R.id.modeUsb : R.id.modeLan);
        updateModeSections();
        mode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                updateModeSections();
                if (id == R.id.modeBt) loadBluetoothDevices(true);
                if (id == R.id.modeUsb) loadUsbDevices();
            }
        });

        startStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (BridgeState.isRunning()) {
                    Config.setAutoStart(MainActivity.this, false);
                    BridgeService.stop(MainActivity.this);
                } else {
                    pending = Pending.START;
                    runPending();
                }
            }
        });
        findViewById(R.id.testPrint).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pending = Pending.TEST;
                runPending();
            }
        });
        findViewById(R.id.btRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadBluetoothDevices(true);
            }
        });
        findViewById(R.id.usbRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadUsbDevices();
            }
        });
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });

        if (Config.MODE_BLUETOOTH.equals(cfg.mode)) loadBluetoothDevices(false);
        if (Config.MODE_USB.equals(cfg.mode)) loadUsbDevices();

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);
        usbReceiverRegistered = true;

        handleUsbAttach(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUsbAttach(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeState.addListener(this);
        if (BridgeState.getStatus().isEmpty()) BridgeState.setStatus(getString(R.string.status_stopped), true);
        onStateChanged();
        updateBatteryUi();
        updateFullScreenUi();
        if (Config.MODE_USB.equals(cfg.mode)) loadUsbDevices();
    }

    @Override
    protected void onPause() {
        saveFromUi();
        BridgeState.removeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (usbReceiverRegistered) unregisterReceiver(usbReceiver);
        if (finder != null) finder.stop();
        super.onDestroy();
    }

    @Override
    public void onStateChanged() {
        status.setText(BridgeState.getStatus());
        status.setTextColor(getColor(BridgeState.isStatusOk() ? R.color.ok_green : R.color.error_red));
        startStop.setText(BridgeState.isRunning() ? R.string.stop : R.string.start);
        log.setText(BridgeState.logText());
        tokenSaved.setText(cfg.hasToken()
                ? getString(R.string.token_saved, BridgeClient.maskToken(cfg.token))
                : getString(R.string.token_none));
    }

    // ---- settings -------------------------------------------------------------------------

    private String selectedMode() {
        int id = mode.getCheckedRadioButtonId();
        if (id == R.id.modeBt) return Config.MODE_BLUETOOTH;
        if (id == R.id.modeUsb) return Config.MODE_USB;
        return Config.MODE_LAN;
    }

    private void updateModeSections() {
        String m = selectedMode();
        lanSection.setVisibility(Config.MODE_LAN.equals(m) ? View.VISIBLE : View.GONE);
        btSection.setVisibility(Config.MODE_BLUETOOTH.equals(m) ? View.VISIBLE : View.GONE);
        usbSection.setVisibility(Config.MODE_USB.equals(m) ? View.VISIBLE : View.GONE);
    }

    /** Copies the form into cfg and SharedPreferences. Returns true if anything changed. */
    private boolean saveFromUi() {
        Config before = Config.load(this);
        cfg.token = token.getText().toString().trim();
        cfg.mode = selectedMode();
        cfg.ip = ip.getText().toString().trim();
        try {
            int p = Integer.parseInt(port.getText().toString().trim());
            cfg.port = p > 0 && p <= 65535 ? p : 9100;
        } catch (NumberFormatException e) {
            cfg.port = 9100;
        }
        int bi = btDevice.getSelectedItemPosition();
        if (bi >= 0 && bi < btDevices.size()) {
            cfg.btAddress = btDevices.get(bi)[0];
            cfg.btName = btDevices.get(bi)[1];
        }
        int ui = usbDevice.getSelectedItemPosition();
        if (ui >= 0 && ui < usbDevices.size()) cfg.usbKey = usbDevices.get(ui)[0];
        cfg.autoStart = before.autoStart;
        cfg.orderAlarm = orderAlarm == null ? before.orderAlarm : orderAlarm.isChecked();
        cfg.save(this);
        return !cfg.token.equals(before.token) || !cfg.mode.equals(before.mode) || !cfg.ip.equals(before.ip)
                || cfg.port != before.port || !cfg.btAddress.equals(before.btAddress)
                || !cfg.usbKey.equals(before.usbKey);
    }

    // ---- actions --------------------------------------------------------------------------

    private void runPending() {
        Pending action = pending;
        if (action == Pending.NONE) return;
        boolean changed = saveFromUi();
        onStateChanged();

        if (action == Pending.LIST_BT) {
            if (!ensureBluetoothPermission()) return;
            pending = Pending.NONE;
            loadBluetoothDevices(false);
            return;
        }
        if (action == Pending.START && !cfg.hasToken()) {
            pending = Pending.NONE;
            toast(getString(R.string.error_no_token));
            return;
        }
        if (Config.MODE_LAN.equals(cfg.mode) && action == Pending.TEST && cfg.ip.isEmpty()) {
            pending = Pending.NONE;
            toast(getString(R.string.error_no_ip));
            return;
        }
        if (Config.MODE_BLUETOOTH.equals(cfg.mode)) {
            if (!ensureBluetoothPermission()) return;
            if (cfg.btAddress.isEmpty()) {
                pending = Pending.NONE;
                loadBluetoothDevices(false);
                toast(getString(R.string.error_no_bt));
                return;
            }
        }
        if (Config.MODE_USB.equals(cfg.mode) && !ensureUsbPermission()) return;
        if (action == Pending.START && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !shouldSkipNotificationAsk()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return; // continues in onRequestPermissionsResult (granted or not)
        }

        pending = Pending.NONE;
        if (action == Pending.START) {
            Config.setAutoStart(this, true);
            cfg.autoStart = true;
            BridgeService.start(this);
        } else if (action == Pending.TEST) {
            testPrint();
        }
        if (changed && BridgeState.isRunning() && action == Pending.TEST) toast(getString(R.string.restart_to_apply));
    }

    private boolean notificationAsked;

    private boolean shouldSkipNotificationAsk() {
        return notificationAsked;
    }

    private boolean ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return false;
        }
        return true;
    }

    private boolean ensureUsbPermission() {
        UsbManager m = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice d = UsbPrinter.find(this, cfg.usbKey);
        if (m == null || d == null) {
            pending = Pending.NONE;
            loadUsbDevices();
            toast(getString(R.string.error_no_usb));
            return false;
        }
        if (m.hasPermission(d)) return true;
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        // Explicit (package-scoped) intent: required for mutable PendingIntents on Android 14.
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        m.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags | PendingIntent.FLAG_UPDATE_CURRENT));
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_NOTIFY) {
            notificationAsked = true;
            if (!granted) toast(getString(R.string.notifications_denied));
            runPending();
        } else if (requestCode == REQ_FIND) {
            startFind(); // searches what it may: WiFi and USB always, Bluetooth if allowed
        } else if (requestCode == REQ_BT) {
            if (granted) {
                loadBluetoothDevices(false);
                runPending();
            } else {
                pending = Pending.NONE;
                btHelp.setText(R.string.bt_permission_needed);
                toast(getString(R.string.bt_permission_needed));
            }
        }
    }

    private void testPrint() {
        final Config c = cfg;
        final Context app = getApplicationContext();
        final String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
        BridgeState.log("test print via " + c.mode);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    Printers.forConfig(app, c).print(EscPos.testTicket(date), "", 0);
                    BridgeState.log("test print sent");
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                    BridgeState.log("test print failed: " + err);
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(error == null ? getString(R.string.test_print_ok)
                                : getString(R.string.test_print_failed, error));
                    }
                });
            }
        }, "dts-test-print").start();
    }

    // ---- device lists ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void loadBluetoothDevices(boolean askPermission) {
        btDevices.clear();
        btHelp.setText(R.string.bt_help);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        boolean allowed = Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        List<String> labels = new ArrayList<>();
        if (!allowed) {
            if (askPermission) {
                pending = Pending.LIST_BT;
                ensureBluetoothPermission();
            }
            btHelp.setText(R.string.bt_permission_needed);
        } else if (adapter == null || !adapter.isEnabled()) {
            labels.add(getString(R.string.bt_off));
        } else {
            List<BluetoothDevice> bonded = new ArrayList<>(adapter.getBondedDevices());
            // Printers (major class IMAGING) first.
            Collections.sort(bonded, new Comparator<BluetoothDevice>() {
                @Override
                public int compare(BluetoothDevice a, BluetoothDevice b) {
                    return Boolean.compare(!isImaging(a), !isImaging(b));
                }
            });
            for (BluetoothDevice d : bonded) {
                String name = d.getName() == null ? d.getAddress() : d.getName();
                btDevices.add(new String[]{d.getAddress(), name});
                labels.add(name + "  (" + d.getAddress() + ")");
            }
            if (bonded.isEmpty()) labels.add(getString(R.string.bt_none));
        }
        if (labels.isEmpty() && !cfg.btAddress.isEmpty()) labels.add(cfg.btName + "  (" + cfg.btAddress + ")");
        setSpinner(btDevice, labels);
        for (int i = 0; i < btDevices.size(); i++) {
            if (btDevices.get(i)[0].equals(cfg.btAddress)) btDevice.setSelection(i);
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean isImaging(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING;
    }

    private void loadUsbDevices() {
        usbDevices.clear();
        List<String> labels = new ArrayList<>();
        for (UsbDevice d : UsbPrinter.candidates(this)) {
            usbDevices.add(new String[]{UsbPrinter.keyOf(d), UsbPrinter.labelOf(d)});
            labels.add(UsbPrinter.labelOf(d));
        }
        if (labels.isEmpty()) labels.add(getString(R.string.usb_none));
        setSpinner(usbDevice, labels);
        for (int i = 0; i < usbDevices.size(); i++) {
            if (usbDevices.get(i)[0].equals(cfg.usbKey)) usbDevice.setSelection(i);
        }
    }

    private void setSpinner(Spinner s, List<String> labels) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
    }

    // ---- find printers --------------------------------------------------------------------

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFindPermissionsThenSearch() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) need.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) need.add(Manifest.permission.BLUETOOTH_SCAN);
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) startFind();
        else requestPermissions(need.toArray(new String[0]), REQ_FIND);
    }

    private void startFind() {
        boolean btAllowed = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT);
        boolean scanAllowed = Build.VERSION.SDK_INT >= 31
                ? granted(Manifest.permission.BLUETOOTH_SCAN)
                : granted(Manifest.permission.ACCESS_FINE_LOCATION);
        findResults.removeAllViews();
        findStatus.setText(R.string.find_searching);
        findButton.setEnabled(false);
        BridgeState.log("find printers started");
        finder.start(new Finder.Callback() {
            @Override
            public void onItem(Finder.Item item) {
                addFindRow(item);
            }

            @Override
            public void onProgress(String text) {
                findStatus.setText(text);
            }

            @Override
            public void onDone(int found) {
                findButton.setEnabled(true);
                findStatus.setText(found == 0 ? getString(R.string.find_none)
                        : getString(R.string.find_done, found));
                BridgeState.log("find printers done: " + found + " found");
            }
        }, btAllowed, scanAllowed);
    }

    private String rowLabel(Finder.Item it) {
        if (Finder.KIND_LAN.equals(it.kind)) return getString(R.string.find_row_lan, it.name, it.ip);
        if (Finder.KIND_BT.equals(it.kind)) return getString(R.string.find_row_bt, it.name);
        if (Finder.KIND_BT_NEW.equals(it.kind)) return getString(R.string.find_row_bt_new, it.name);
        return getString(R.string.find_row_usb, it.name);
    }

    private void addFindRow(final Finder.Item it) {
        Button b = new Button(this);
        b.setText(rowLabel(it));
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        b.setTextSize(17);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose(it);
            }
        });
        findResults.addView(b, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void choose(final Finder.Item it) {
        finder.stop();
        findButton.setEnabled(true);
        if (Finder.KIND_LAN.equals(it.kind)) {
            mode.check(R.id.modeLan);
            ip.setText(it.ip);
            port.setText(String.valueOf(it.port));
            selected(it.name + " (" + it.ip + ")");
        } else if (Finder.KIND_BT.equals(it.kind)) {
            useBluetooth(it.btAddress, it.name);
        } else if (Finder.KIND_BT_NEW.equals(it.kind)) {
            findStatus.setText(getString(R.string.find_pairing, it.name));
            finder.pair(it.btAddress, new Finder.PairCallback() {
                @Override
                public void onPaired(String address, String name) {
                    BridgeState.log("paired " + name);
                    useBluetooth(address, name);
                }

                @Override
                public void onPairFailed(String name) {
                    BridgeState.log("pairing failed: " + name);
                    findStatus.setText(getString(R.string.find_pair_failed, name));
                }
            });
        } else {
            useUsb(it.usbKey, it.name);
        }
    }

    private void useBluetooth(String address, String name) {
        cfg.btAddress = address;
        cfg.btName = name;
        mode.check(R.id.modeBt);
        loadBluetoothDevices(false);
        selected(name);
    }

    private void useUsb(String key, String name) {
        cfg.usbKey = key;
        mode.check(R.id.modeUsb);
        loadUsbDevices();
        selected(name);
        pending = Pending.NONE;
        ensureUsbPermission(); // asks for access now, so Test print works straight away
    }

    private void selected(String what) {
        boolean changed = saveFromUi();
        String msg = getString(R.string.find_selected, what);
        findStatus.setText(msg);
        toast(msg);
        BridgeState.log("printer chosen: " + what);
        if (changed && BridgeState.isRunning()) toast(getString(R.string.restart_to_apply));
    }

    /** Opened because a USB printer was plugged in: select it straight away. */
    private void handleUsbAttach(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) return;
        UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (d == null) return;
        for (UsbDevice c : UsbPrinter.candidates(this)) {
            if (UsbPrinter.keyOf(c).equals(UsbPrinter.keyOf(d))) {
                String label = UsbPrinter.labelOf(d);
                toast(getString(R.string.find_usb_attached, label));
                useUsb(UsbPrinter.keyOf(d), label);
                return;
            }
        }
    }

    // ---- full-screen alerts (Android 14+) -------------------------------------------------

    private void updateFullScreenUi() {
        boolean missing = false;
        if (Build.VERSION.SDK_INT >= 34) {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            missing = nm != null && !nm.canUseFullScreenIntent();
        }
        findViewById(R.id.alarmPermHelp).setVisibility(missing ? View.VISIBLE : View.GONE);
        View b = findViewById(R.id.alarmPerm);
        b.setVisibility(missing ? View.VISIBLE : View.GONE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
                            Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                }
            }
        });
    }

    // ---- battery --------------------------------------------------------------------------

    private boolean ignoringBatteryOptimisations() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void updateBatteryUi() {
        boolean ok = ignoringBatteryOptimisations();
        batteryHelp.setText(ok ? R.string.battery_ok : R.string.battery_help);
        battery.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    @SuppressLint("BatteryLife")
    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
