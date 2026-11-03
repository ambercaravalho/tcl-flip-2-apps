package com.amber.tcltweaks;

import android.app.NotificationManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * TCL's SystemUI posts "Battery full / Battery is fully charged." (id 3000) from
 * BatteryControllerImpl.showBatteryFullNotification, with a chime and a short
 * vibration, every time the battery reaches 100% on a charger. No setting turns
 * it off, and its channel (BAT) also carries the low-battery and battery saver
 * warnings, so blocking the channel would lose those too. This skips just that
 * method and clears any copy that's already showing.
 */
public class BatteryFullHook implements IXposedHookLoadPackage {

    private static final String SYSTEMUI = "com.android.systemui";
    private static final String CONTROLLER =
        "com.android.systemui.statusbar.policy.BatteryControllerImpl";
    private static final String METHOD = "showBatteryFullNotification";
    private static final int NOTIFICATION_ID = 3000;
    private static final String TAG = "TclTweaks: ";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!SYSTEMUI.equals(lpparam.packageName)) {
            return;
        }
        Class<?> controller;
        try {
            controller = Class.forName(CONTROLLER, false, lpparam.classLoader);
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + CONTROLLER + " not found");
            return;
        }
        Field manager = null;
        try {
            manager = controller.getDeclaredField("mNotificationManager");
            manager.setAccessible(true);
        } catch (NoSuchFieldException e) {
            XposedBridge.log(TAG + "mNotificationManager not found; won't clear old notifications");
        }
        final Field nm = manager;

        int hooked = 0;
        for (Method m : controller.getDeclaredMethods()) {
            if (!m.getName().equals(METHOD)) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                    if (nm == null) {
                        return;
                    }
                    try {
                        Object n = nm.get(param.thisObject);
                        if (n instanceof NotificationManager) {
                            ((NotificationManager) n).cancel(NOTIFICATION_ID);
                        }
                    } catch (IllegalAccessException e) {
                        XposedBridge.log(TAG + "couldn't clear notification: " + e);
                    }
                }
            });
            hooked++;
        }
        XposedBridge.log(TAG + (hooked > 0
            ? "hooked " + METHOD + " (battery-full notification off)"
            : METHOD + " not found"));
    }
}
