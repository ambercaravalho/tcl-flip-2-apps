package com.amber.dumbdownunhide;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * DumbDown Launcher builds its app drawer from every launchable activity, minus a
 * hardcoded static Set of package names in AllAppsActivity, then appends its own
 * virtual tiles (quack, rideshare, ...). The home grid is resolved against the
 * drawer's list.
 *
 * Unhiding: the denylist Set is read on every rebuild, so removing entries from
 * it once at process start unhides those apps everywhere.
 *
 * Hiding: virtual tiles never pass through the denylist, but every tile list
 * (drawer, default grid) ends with the launcher's subscription-lock pass, so its
 * return value is filtered. One coroutine also injects tiles (quack, contact
 * sync) straight into the open drawer's on-screen list and into the drawer cache
 * the home grid reads, so both are scrubbed after it runs.
 */
public class UnhideHook implements IXposedHookLoadPackage {

    /** Packages to take off the launcher's denylist. */
    private static final String[] UNHIDE = {
        "com.topjohnwu.magisk",
        "com.android.chrome",
        "org.chromium.chrome",
    };

    /** Tile IDs (package names, or the launcher's __VIRTUAL__ tile IDs) to hide. */
    private static final Set<String> HIDE = new HashSet<>(Arrays.asList(
        "com.offlineinc.dumbtt9",                                 // dumb keys
        "__TWO_FACTOR__", "__DEVICE_SETUP__", "__QUACK__",
        "__DUMB_RIDE__", "__DUMB_SIGNAL__", "__CALENDAR__",
        "__FREE_UP_SPACE__",
        "com.openbubbles.messaging", "__GOOGLE_MESSAGES__"));     // smart txt

    private static final String LAUNCHER = "com.offlineinc.dumbdownlauncher";
    private static final String ALL_APPS_ACTIVITY = LAUNCHER + ".AllAppsActivity";

    // The field name is obfuscated and changes between builds, so the denylist is
    // found by content. The launcher always hides itself, so its own package name
    // identifies the set even after other entries are removed.
    private static final String DENYLIST_MARKER = LAUNCHER;

    // Obfuscated names from the launcher build kept in apks/. A launcher update
    // will likely rename these; the hook logs "not found" when that happens.
    private static final String TILE_FILTER_CLASS = "bc.g1";
    private static final String TILE_INJECTOR_CLASS = "bc.j";
    private static final String TILE_ID_FIELD = "a";

    private static final String TAG = "DumbDownUnhide: ";

    private static final AtomicBoolean loggedFirstHide = new AtomicBoolean();
    private static volatile TileIdField tileIdField;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!LAUNCHER.equals(lpparam.packageName)) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        try {
            unhide(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "unhide failed: " + t);
        }
        try {
            hookTileFilter(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "tile filter hook failed: " + t);
        }
        try {
            hookTileInjector(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "drawer scrub hook failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void unhide(ClassLoader cl) throws Exception {
        Class<?> cls = Class.forName(ALL_APPS_ACTIVITY, true, cl);
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !Set.class.isAssignableFrom(f.getType())) {
                continue;
            }
            f.setAccessible(true);
            Object value = f.get(null);
            if (!(value instanceof Set) || !((Set<?>) value).contains(DENYLIST_MARKER)) {
                continue;
            }

            Set<Object> denylist = (Set<Object>) value;
            List<String> removed = new ArrayList<>();
            for (String pkg : UNHIDE) {
                if (denylist.contains(pkg)) {
                    removed.add(pkg);
                }
            }
            if (removed.isEmpty()) {
                XposedBridge.log(TAG + "nothing to unhide in " + cls.getName() + "." + f.getName());
                return;
            }

            String how;
            try {
                denylist.removeAll(removed);
                how = "in place";
            } catch (UnsupportedOperationException immutable) {
                Set<Object> copy = new LinkedHashSet<>(denylist);
                copy.removeAll(removed);
                f.set(null, copy);
                how = "by replacing the set";
            }
            XposedBridge.log(TAG + "unhid " + removed + " " + how + " ("
                + cls.getName() + "." + f.getName() + ")");
            return;
        }
        XposedBridge.log(TAG + "denylist not found in " + cls.getName()
            + " - the launcher was probably updated");
    }

    private static void hookTileFilter(ClassLoader cl) {
        Class<?> cls;
        try {
            cls = Class.forName(TILE_FILTER_CLASS, false, cl);
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + "tile filter " + TILE_FILTER_CLASS
                + " not found - the launcher was probably updated");
            return;
        }

        Method target = null;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == ArrayList.class
                    && params.length > 0 && params[0] == ArrayList.class) {
                if (target != null) {
                    XposedBridge.log(TAG + "tile filter " + TILE_FILTER_CLASS
                        + " has more than one candidate method - the launcher was probably updated");
                    return;
                }
                target = m;
            }
        }
        if (target == null) {
            XposedBridge.log(TAG + "tile filter method not found in " + TILE_FILTER_CLASS
                + " - the launcher was probably updated");
            return;
        }

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (result instanceof List) {
                    param.setResult(withoutHidden((List<?>) result));
                }
            }
        });
        XposedBridge.log(TAG + "hiding tiles via " + TILE_FILTER_CLASS + "." + target.getName());
    }

    private static void hookTileInjector(ClassLoader cl) throws Exception {
        Class<?> injector;
        try {
            injector = Class.forName(TILE_INJECTOR_CLASS, false, cl);
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + "tile injector " + TILE_INJECTOR_CLASS
                + " not found - the launcher was probably updated");
            return;
        }
        Method invokeSuspend;
        try {
            invokeSuspend = injector.getDeclaredMethod("invokeSuspend", Object.class);
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + TILE_INJECTOR_CLASS
                + " is not a coroutine any more - the launcher was probably updated");
            return;
        }

        Class<?> allApps = Class.forName(ALL_APPS_ACTIVITY, true, cl);

        // Static ArrayList cache, read by the home grid and when the drawer reopens.
        final List<Field> caches = new ArrayList<>();
        // Instance lists on the open drawer, including the one it renders.
        final List<Field> drawerLists = new ArrayList<>();
        for (Field f : allApps.getDeclaredFields()) {
            boolean isStatic = Modifier.isStatic(f.getModifiers());
            if (isStatic && f.getType() == ArrayList.class) {
                f.setAccessible(true);
                caches.add(f);
            } else if (!isStatic && List.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                drawerLists.add(f);
            }
        }
        // The coroutine holds the drawer it's updating.
        final List<Field> activityRefs = new ArrayList<>();
        for (Field f : injector.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && allApps.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                activityRefs.add(f);
            }
        }
        if (caches.isEmpty() && (drawerLists.isEmpty() || activityRefs.isEmpty())) {
            XposedBridge.log(TAG + "drawer lists not found in " + allApps.getName()
                + " - the launcher was probably updated");
            return;
        }

        XposedBridge.hookMethod(invokeSuspend, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                for (Field ref : activityRefs) {
                    try {
                        Object activity = ref.get(param.thisObject);
                        if (activity == null) {
                            continue;
                        }
                        for (Field f : drawerLists) {
                            Object value = f.get(activity);
                            if (value instanceof List) {
                                removeHidden((List<?>) value);
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "drawer list scrub failed: " + t);
                    }
                }
                for (Field f : caches) {
                    try {
                        Object value = f.get(null);
                        if (!(value instanceof List)) {
                            continue;
                        }
                        List<?> list = (List<?>) value;
                        ArrayList<Object> filtered = withoutHidden(list);
                        if (filtered.size() != list.size()) {
                            f.set(null, filtered);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "cache scrub failed: " + t);
                    }
                }
            }
        });
        XposedBridge.log(TAG + "scrubbing drawer after " + TILE_INJECTOR_CLASS + ".invokeSuspend ("
            + drawerLists.size() + " drawer lists, " + caches.size() + " caches)");
    }

    private static ArrayList<Object> withoutHidden(List<?> list) {
        ArrayList<Object> kept = new ArrayList<>(list.size());
        List<String> hidden = null;
        for (Object item : list) {
            String id = tileId(item);
            if (id != null && HIDE.contains(id)) {
                if (hidden == null) {
                    hidden = new ArrayList<>();
                }
                hidden.add(id);
            } else {
                kept.add(item);
            }
        }
        logFirstHide(hidden);
        return kept;
    }

    /**
     * Removes hidden tiles in place. The drawer renders an observable list held
     * in a final field, so it can't be swapped for a filtered copy.
     */
    private static void removeHidden(List<?> list) {
        List<Object> drop = null;
        List<String> hidden = null;
        for (Object item : list) {
            String id = tileId(item);
            if (id != null && HIDE.contains(id)) {
                if (drop == null) {
                    drop = new ArrayList<>();
                    hidden = new ArrayList<>();
                }
                drop.add(item);
                hidden.add(id);
            }
        }
        if (drop != null) {
            list.removeAll(drop);
            logFirstHide(hidden);
        }
    }

    private static void logFirstHide(List<String> hidden) {
        if (hidden != null && loggedFirstHide.compareAndSet(false, true)) {
            XposedBridge.log(TAG + "hid tiles " + hidden);
        }
    }

    private static String tileId(Object item) {
        if (item == null) {
            return null;
        }
        Class<?> cls = item.getClass();
        TileIdField cached = tileIdField;
        Field f;
        if (cached != null && cached.owner == cls) {
            f = cached.field;
        } else {
            try {
                f = cls.getDeclaredField(TILE_ID_FIELD);
            } catch (NoSuchFieldException e) {
                return null;
            }
            if (f.getType() != String.class) {
                return null;
            }
            f.setAccessible(true);
            tileIdField = new TileIdField(cls, f);
        }
        try {
            return (String) f.get(item);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static final class TileIdField {
        final Class<?> owner;
        final Field field;

        TileIdField(Class<?> owner, Field field) {
            this.owner = owner;
            this.field = field;
        }
    }
}
