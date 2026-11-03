package com.amber.settingslight;

import android.content.res.Configuration;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * TCL's Settings only half supports dark mode: backgrounds switch, but much of
 * its text is black from resources and from colours hardcoded in code. Rather
 * than fix it screen by screen, this keeps the app in light mode while the rest
 * of the phone stays dark.
 *
 * Every Resources object in the process gets its configuration through
 * ResourcesImpl.updateConfiguration, both when it's created and on each
 * configuration change, so clearing the night bit there makes the app resolve
 * light resources and themes everywhere.
 */
public class LightModeHook implements IXposedHookLoadPackage {

    private static final String SETTINGS = "com.android.settings";
    private static final String TAG = "SettingsLight: ";

    private static final AtomicBoolean loggedFirstForce = new AtomicBoolean();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!SETTINGS.equals(lpparam.packageName)) {
            return;
        }
        Class<?> impl = Class.forName("android.content.res.ResourcesImpl");
        Method target = null;
        for (Method m : impl.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (m.getName().equals("updateConfiguration") && params.length > 0
                    && params[0] == Configuration.class) {
                target = m;
                break;
            }
        }
        if (target == null) {
            XposedBridge.log(TAG + "ResourcesImpl.updateConfiguration not found");
            return;
        }
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Configuration config = (Configuration) param.args[0];
                if (config == null
                        || (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES) {
                    return;
                }
                // The caller may keep using its Configuration, so change a copy.
                Configuration light = new Configuration(config);
                light.uiMode = (light.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | Configuration.UI_MODE_NIGHT_NO;
                param.args[0] = light;
                if (loggedFirstForce.compareAndSet(false, true)) {
                    XposedBridge.log(TAG + "forced light mode");
                }
            }
        });
        XposedBridge.log(TAG + "hooked ResourcesImpl.updateConfiguration");
    }
}
