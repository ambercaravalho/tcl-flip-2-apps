package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-only stub; LSPosed provides the real class at runtime. */
public final class XposedBridge {
    private XposedBridge() {
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }
}
