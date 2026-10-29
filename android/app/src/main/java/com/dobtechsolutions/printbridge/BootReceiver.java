package com.dobtechsolutions.printbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the bridge after a reboot or app update if it was running (token saved + Start pressed). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Config c = Config.load(ctx);
        if (!c.hasToken() || !c.autoStart) return;
        try {
            BridgeService.start(ctx);
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException on unusual OEM builds
            BridgeState.log("auto-start failed: " + e.getMessage());
        }
    }
}
