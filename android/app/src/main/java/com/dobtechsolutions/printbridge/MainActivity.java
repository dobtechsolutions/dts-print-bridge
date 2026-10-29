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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
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

    private enum Pending { NONE, START, TEST, LIST_BT }

    private EditText token, ip, port;
    private TextView tokenSaved, status, log, btHelp, batteryHelp;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeState.addListener(this);
        if (BridgeState.getStatus().isEmpty()) BridgeState.setStatus(getString(R.string.status_stopped), true);
        onStateChanged();
        updateBatteryUi();
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
