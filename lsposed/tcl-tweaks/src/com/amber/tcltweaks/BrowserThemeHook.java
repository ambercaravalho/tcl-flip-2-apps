package com.amber.tcltweaks;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * The phone's Chromium follows the system dark mode badly: the toolbar gets
 * white text on light gray and pages are darkened. Faking light mode in its
 * Resources (as LightModeHook does for Settings) isn't enough, because part of
 * Chromium still thinks it's dark and its page darkening then fails on the
 * PowerVR GPU, leaving pages blank. Chromium's own Theme setting has no such
 * problem, so this sets it to Light each time the browser starts.
 */
public class BrowserThemeHook implements IXposedHookLoadPackage {

    private static final String CHROMIUM = "org.chromium.chrome";
    // ChromePreferenceKeys.UI_THEME_SETTING; ThemeType: 0 system default, 1 light, 2 dark.
    private static final String THEME_KEY = "ui_theme_setting";
    private static final int THEME_LIGHT = 1;
    private static final String TAG = "TclTweaks: ";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!CHROMIUM.equals(lpparam.packageName)
                || !CHROMIUM.equals(lpparam.processName)) {
            return;
        }
        // Runs before Application.onCreate, so before any activity reads the theme.
        XposedBridge.hookMethod(
            Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class),
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.args[0];
                    SharedPreferences prefs = app.getSharedPreferences(
                        app.getPackageName() + "_preferences", Context.MODE_PRIVATE);
                    if (prefs.getInt(THEME_KEY, 0) != THEME_LIGHT) {
                        prefs.edit().putInt(THEME_KEY, THEME_LIGHT).apply();
                        XposedBridge.log(TAG + "set browser theme to light");
                    }
                }
            });
        XposedBridge.log(TAG + "hooked browser startup (theme stays light)");
    }
}
