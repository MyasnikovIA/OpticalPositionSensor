package ru.miacomsoft.optical_position_sensor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Log.i(TAG, "onReceive: " + action);

        boolean relevant =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                        || "android.intent.action.MY_PACKAGE_REPLACED".equals(action)
                        || "android.intent.action.PACKAGE_REPLACED".equals(action);

        if (!relevant) return;

        SensorConfig cfg;
        try {
            cfg = ConfigStore.load(context);
        } catch (Exception e) {
            Log.e(TAG, "ConfigStore.load failed", e);
            return;
        }

        if (cfg == null || !cfg.enabled) {
            Log.i(TAG, "Sensor disabled in config — skip auto-start");
            return;
        }

        try {
            Intent svc = new Intent(context, SensorService.class);
            svc.setAction(SensorService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            Log.i(TAG, "SensorService start requested (httpPort="
                    + cfg.httpPort + ", wsPort=" + cfg.wsPort + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SensorService", e);
        }
    }
}