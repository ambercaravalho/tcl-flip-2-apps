package com.amber.dumbdownunhide;

import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * the home grid reads, so both are scrubbed after it runs. The same passes
 * relabel the tiles in RENAME.
 *
 * Both of those are obfuscated and renamed by every launcher build, so they are
 * found by the string constants they load (see {@link DexScanner}) and the result
 * is cached until the launcher APK changes.
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
        "__TWO_FACTOR__", "__DEVICE_SETUP__",
        "__DUMB_RIDE__", "__DUMB_SIGNAL__", "__CALENDAR__",
        "__FREE_UP_SPACE__",
        "com.openbubbles.messaging", "__GOOGLE_MESSAGES__",       // smart txt
        "__CONTACT_SYNC__"));

    /** Tile IDs to relabel, mapped to the label to show instead. */
    private static final Map<String, String> RENAME = new HashMap<>();
    static {
        RENAME.put("__DUMB_SPOTIFY__", "spotify");                // smart music
    }

    private static final String LAUNCHER = "com.offlineinc.dumbdownlauncher";
    private static final String ALL_APPS_ACTIVITY = LAUNCHER + ".AllAppsActivity";

    // The field name is obfuscated and changes between builds, so the denylist is
    // found by content. The launcher always hides itself, so its own package name
    // identifies the set even after other entries are removed.
    private static final String DENYLIST_MARKER = LAUNCHER;

    /** The subscription-lock pass: {@code static ArrayList f(ArrayList tiles, prefs)}. */
    private static final Fingerprint TILE_FILTER = new Fingerprint("filter", "tile filter",
            "has_subscription", "hide_audio_bundle", "hide_smart_txt") {
        @Override
        boolean matches(Method m) {
            Class<?>[] params = m.getParameterTypes();
            return Modifier.isStatic(m.getModifiers()) && m.getReturnType() == ArrayList.class
                && params.length > 0 && params[0] == ArrayList.class;
        }
    };

    /** The coroutine body that injects the quack and contact sync tiles. */
    private static final Fingerprint TILE_INJECTOR = new Fingerprint("injector", "tile injector",
            "__CONTACT_SYNC__", "__QUACK__") {
        @Override
        boolean matches(Method m) {
            Class<?>[] params = m.getParameterTypes();
            return !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == Object.class
                && params.length == 1 && params[0] == Object.class;
        }
    };

    private static final Fingerprint[] FINGERPRINTS = {TILE_FILTER, TILE_INJECTOR};

    // Bump when a fingerprint changes, so results cached by an older module build are dropped.
    private static final int CACHE_VERSION = 1;
    private static final String CACHE_FILE = "cache/dumbdown-unhide.properties";
    private static final String CACHE_NONE = "-";

    /** Tiles are a Kotlin data class whose toString() reads "AppItem(packageName=..., label=..., ...)". */
    private static final String TILE_ID_KEY = "packageName=";
    private static final String TILE_LABEL_KEY = ", label=";

    private static final String TAG = "DumbDownUnhide: ";

    private static final AtomicBoolean loggedFirstHide = new AtomicBoolean();
    private static final AtomicBoolean loggedFirstRename = new AtomicBoolean();
    private static final Map<Class<?>, TileFields> tileFields = new ConcurrentHashMap<>();
    private static final TileFields NOT_A_TILE = new TileFields(null, null);

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
        Map<Fingerprint, Method> methods;
        try {
            methods = resolve(lpparam.appInfo, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "launcher code lookup failed: " + t);
            return;
        }
        try {
            hookTileFilter(methods.get(TILE_FILTER));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "tile filter hook failed: " + t);
        }
        try {
            hookTileInjector(cl, methods.get(TILE_INJECTOR));
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

    /**
     * Maps each fingerprint to the launcher method it matches, leaving out any that
     * match nothing or more than one method. Scanning the dex only happens when the
     * launcher APK differs from the one the cache was written for.
     */
    private static Map<Fingerprint, Method> resolve(ApplicationInfo app, ClassLoader cl)
            throws IOException {
        File apk = new File(app.sourceDir);
        String key = CACHE_VERSION + " " + apk.getPath() + " " + apk.length() + " " + apk.lastModified();
        File cacheFile = new File(app.dataDir, CACHE_FILE);

        Map<Fingerprint, Method> resolved = fromCache(readCache(cacheFile), key, cl);
        if (resolved != null) {
            return resolved;
        }

        Set<String> anchors = new HashSet<>();
        for (Fingerprint fp : FINGERPRINTS) {
            anchors.addAll(fp.strings);
        }
        long start = System.nanoTime();
        List<DexScanner.Hit> hits = DexScanner.find(app.sourceDir, anchors);
        XposedBridge.log(TAG + "scanned launcher dex in " + (System.nanoTime() - start) / 1000000 + " ms");

        resolved = new HashMap<>();
        Properties cache = new Properties();
        cache.setProperty("key", key);
        for (Fingerprint fp : FINGERPRINTS) {
            Method m = match(fp, hits, cl);
            if (m != null) {
                resolved.put(fp, m);
            }
            cache.setProperty(fp.id, m != null ? spec(m) : CACHE_NONE);
        }
        writeCache(cacheFile, cache);
        return resolved;
    }

    /** Returns null when the cache is missing, stale, or names a method that no longer fits. */
    private static Map<Fingerprint, Method> fromCache(Properties cache, String key, ClassLoader cl) {
        if (cache == null || !key.equals(cache.getProperty("key"))) {
            return null;
        }
        Map<Fingerprint, Method> resolved = new HashMap<>();
        for (Fingerprint fp : FINGERPRINTS) {
            String spec = cache.getProperty(fp.id);
            if (CACHE_NONE.equals(spec)) {
                XposedBridge.log(TAG + fp.label + " not found (cached) - the launcher was probably updated");
                continue;
            }
            Method m = spec != null ? fromSpec(spec, cl) : null;
            if (m == null || !fp.matches(m)) {
                return null;
            }
            resolved.put(fp, m);
        }
        return resolved;
    }

    private static Method match(Fingerprint fp, List<DexScanner.Hit> hits, ClassLoader cl) {
        List<Method> candidates = new ArrayList<>();
        for (DexScanner.Hit hit : hits) {
            if (!hit.strings.containsAll(fp.strings)) {
                continue;
            }
            Method m = findMethod(cl, hit.className, hit.methodName, hit.descriptor);
            if (m != null && fp.matches(m)) {
                candidates.add(m);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            XposedBridge.log(TAG + fp.label + " not found - the launcher was probably updated");
        } else {
            XposedBridge.log(TAG + fp.label + " is ambiguous " + candidates
                + " - the launcher was probably updated");
        }
        return null;
    }

    private static Method findMethod(ClassLoader cl, String className, String name, String descriptor) {
        Class<?> cls;
        try {
            cls = Class.forName(className, false, cl);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && descriptor(m).equals(descriptor)) {
                return m;
            }
        }
        return null;
    }

    private static String spec(Method m) {
        return m.getDeclaringClass().getName() + " " + m.getName() + " " + descriptor(m);
    }

    private static Method fromSpec(String spec, ClassLoader cl) {
        String[] parts = spec.split(" ", 3);
        return parts.length == 3 ? findMethod(cl, parts[0], parts[1], parts[2]) : null;
    }

    private static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(descriptor(p));
        }
        return sb.append(')').append(descriptor(m.getReturnType())).toString();
    }

    private static String descriptor(Class<?> c) {
        if (c.isArray()) {
            return c.getName().replace('.', '/');
        }
        if (!c.isPrimitive()) {
            return "L" + c.getName().replace('.', '/') + ";";
        }
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        return "D";
    }

    private static Properties readCache(File file) {
        if (!file.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeCache(File file, Properties cache) {
        File tmp = new File(file.getPath() + ".tmp");
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(tmp)) {
            cache.store(out, null);
        } catch (IOException e) {
            XposedBridge.log(TAG + "couldn't write " + file + ": " + e);
            return;
        }
        if (!tmp.renameTo(file)) {
            XposedBridge.log(TAG + "couldn't write " + file);
        }
    }

    private static void hookTileFilter(Method target) {
        if (target == null) {
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
        XposedBridge.log(TAG + "hiding tiles via " + target.getDeclaringClass().getName()
            + "." + target.getName());
    }

    private static void hookTileInjector(ClassLoader cl, Method target) throws Exception {
        if (target == null) {
            return;
        }
        final Class<?> allApps = Class.forName(ALL_APPS_ACTIVITY, true, cl);

        // R8 merges coroutine classes and widens field types to Object, so every
        // field is checked by the value it holds when the coroutine runs.
        final List<Field> activityRefs = new ArrayList<>();
        for (Field f : target.getDeclaringClass().getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType().isAssignableFrom(allApps)) {
                f.setAccessible(true);
                activityRefs.add(f);
            }
        }
        // Instance lists on the open drawer, including the one it renders, and
        // static caches, read by the home grid and when the drawer reopens.
        final List<Field> drawerLists = new ArrayList<>();
        final List<Field> caches = new ArrayList<>();
        for (Field f : allApps.getDeclaredFields()) {
            int mod = f.getModifiers();
            Class<?> type = f.getType();
            if (!Modifier.isStatic(mod)) {
                if (type.isAssignableFrom(List.class) || List.class.isAssignableFrom(type)) {
                    f.setAccessible(true);
                    drawerLists.add(f);
                }
            } else if (!Modifier.isFinal(mod) && type.isAssignableFrom(ArrayList.class)) {
                f.setAccessible(true);
                caches.add(f);
            }
        }

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                for (Field ref : activityRefs) {
                    try {
                        Object activity = ref.get(param.thisObject);
                        if (!allApps.isInstance(activity)) {
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
        XposedBridge.log(TAG + "scrubbing drawer after " + target.getDeclaringClass().getName()
            + "." + target.getName());
    }

    private static ArrayList<Object> withoutHidden(List<?> list) {
        ArrayList<Object> kept = new ArrayList<>(list.size());
        List<String> hidden = null;
        for (Object item : list) {
            TileFields tile = tileFields(item);
            String id = tile != null ? tile.id(item) : null;
            if (id != null && HIDE.contains(id)) {
                if (hidden == null) {
                    hidden = new ArrayList<>();
                }
                hidden.add(id);
            } else {
                relabel(tile, item, id);
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
            TileFields tile = tileFields(item);
            String id = tile != null ? tile.id(item) : null;
            if (id != null && HIDE.contains(id)) {
                if (drop == null) {
                    drop = new ArrayList<>();
                    hidden = new ArrayList<>();
                }
                drop.add(item);
                hidden.add(id);
            } else {
                relabel(tile, item, id);
            }
        }
        if (drop != null) {
            list.removeAll(drop);
            logFirstHide(hidden);
        }
    }

    private static void relabel(TileFields tile, Object item, String id) {
        String label = id != null ? RENAME.get(id) : null;
        if (label == null || tile.label == null) {
            return;
        }
        try {
            Object old = tile.label.get(item);
            if (!label.equals(old)) {
                tile.label.set(item, label);
                if (loggedFirstRename.compareAndSet(false, true)) {
                    XposedBridge.log(TAG + "renamed tile " + id + " from \"" + old + "\" to \"" + label + "\"");
                }
            }
        } catch (IllegalAccessException e) {
            XposedBridge.log(TAG + "couldn't rename tile " + id + ": " + e);
        }
    }

    private static void logFirstHide(List<String> hidden) {
        if (hidden != null && loggedFirstHide.compareAndSet(false, true)) {
            XposedBridge.log(TAG + "hid tiles " + hidden);
        }
    }

    /** Returns the tile's ID and label fields, or null if {@code item} isn't a tile. */
    private static TileFields tileFields(Object item) {
        if (item == null) {
            return null;
        }
        Class<?> cls = item.getClass();
        TileFields fields = tileFields.get(cls);
        if (fields == null) {
            fields = findTileFields(item);
            if (fields == null) {
                return null;
            }
            tileFields.put(cls, fields);
        }
        return fields != NOT_A_TILE ? fields : null;
    }

    /**
     * Picks the String fields whose values toString() prints after "packageName="
     * and ", label=". Returns NOT_A_TILE for classes that don't print an ID, or
     * null when this item can't tell the fields apart, so the next item is tried
     * instead. A tile class that stops printing its label just can't be relabeled.
     */
    private static TileFields findTileFields(Object item) {
        String text;
        try {
            text = String.valueOf(item);
        } catch (Throwable t) {
            return NOT_A_TILE;
        }
        if (!text.contains(TILE_ID_KEY)) {
            return NOT_A_TILE;
        }
        Field id = fieldPrintedAfter(item, text, TILE_ID_KEY);
        if (id == null) {
            return null;
        }
        Field label = null;
        if (text.contains(TILE_LABEL_KEY)) {
            label = fieldPrintedAfter(item, text, TILE_LABEL_KEY);
            if (label == null) {
                return null;
            }
        }
        return new TileFields(id, label);
    }

    /** The one String field whose value follows {@code key} in {@code text}, or null. */
    private static Field fieldPrintedAfter(Object item, String text, String key) {
        int start = text.indexOf(key) + key.length();
        Field match = null;
        for (Field f : item.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            f.setAccessible(true);
            String value;
            try {
                value = (String) f.get(item);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (value == null || !text.startsWith(value, start)) {
                continue;
            }
            int end = start + value.length();
            if (end < text.length() && (text.charAt(end) == ',' || text.charAt(end) == ')')) {
                if (match != null) {
                    return null;
                }
                match = f;
            }
        }
        return match;
    }

    /** A launcher method identified by the string constants it loads and its signature. */
    private abstract static class Fingerprint {
        /** Cache key. */
        final String id;
        final String label;
        final Set<String> strings;

        Fingerprint(String id, String label, String... strings) {
            this.id = id;
            this.label = label;
            this.strings = new HashSet<>(Arrays.asList(strings));
        }

        abstract boolean matches(Method m);
    }

    private static final class TileFields {
        final Field id;
        /** Null when the tile class doesn't print its label. */
        final Field label;

        TileFields(Field id, Field label) {
            this.id = id;
            this.label = label;
        }

        String id(Object item) {
            try {
                return (String) id.get(item);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
    }
}
