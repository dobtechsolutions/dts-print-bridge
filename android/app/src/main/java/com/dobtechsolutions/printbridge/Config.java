package com.dobtechsolutions.printbridge;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings stored in SharedPreferences. */
public final class Config {
    public static final String MODE_LAN = "lan";
    public static final String MODE_BLUETOOTH = "bluetooth";
    public static final String MODE_USB = "usb";

    private static final String PREFS = "dts_print_bridge";

    public String token = "";
    public String mode = MODE_LAN;
    public String ip = "";
    public int port = 9100;
    public String btAddress = "";
    public String btName = "";
    /** "vendorId:productId" of the USB printer (device names change on replug). */
    public String usbKey = "";
    /** True after the user pressed Start, false after Stop. Used to restart on boot. */
    public boolean autoStart = false;

    public boolean hasToken() {
        return token != null && !token.trim().isEmpty();
    }

    public static Config load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config c = new Config();
        c.token = p.getString("token", "");
        c.mode = p.getString("mode", MODE_LAN);
        c.ip = p.getString("ip", "");
        c.port = p.getInt("port", 9100);
        c.btAddress = p.getString("btAddress", "");
        c.btName = p.getString("btName", "");
        c.usbKey = p.getString("usbKey", "");
        c.autoStart = p.getBoolean("autoStart", false);
        return c;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("token", token == null ? "" : token.trim())
                .putString("mode", mode)
                .putString("ip", ip == null ? "" : ip.trim())
                .putInt("port", port)
                .putString("btAddress", btAddress)
                .putString("btName", btName)
                .putString("usbKey", usbKey)
                .putBoolean("autoStart", autoStart)
                .apply();
    }

    public static void setAutoStart(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autoStart", on).apply();
    }
}
