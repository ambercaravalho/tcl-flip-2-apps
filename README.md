# tcl-flip-2-apps

Fixes for the TCL Flip 2 / TCL Flip Go, packaged as two Magisk modules and one LSPosed module.
Also links to the official releases of the phone's stock offlineinc apps so they can be reinstalled
if you remove or break one, and notes on getting microG onto the phone, which needs no code from
this repo but is fiddly enough to be worth writing down.

| Path                     | What it is                                                          |
| ------------------------ | ------------------------------------------------------------------- |
| `magisk/launcher/`       | Unlocks the app tiles and skips the failing activation screen.      |
| `magisk/lsposed-notif/`  | Silences LSPosed's persistent "LSPosed loaded" status notification. |
| `lsposed/unhide-apps/`   | Shows Magisk and Chrome in the launcher, and hides unused tiles.    |

<details>
<summary><b>The phone</b></summary>

The TCL Flip 2 (model `4058G`) and the TCL Flip Go are cheap flip phones with a 240x320 screen.
They look like feature phones but run Android 11 (SDK 30), and the home screen is **DumbDown
Launcher** (`com.offlineinc.dumbdownlauncher`) — a locked-down launcher that only shows the apps
your plan is supposed to include. Two companion apps ship with it: DumbTT9
(`com.offlineinc.dumbtt9`, the T9 keyboard) and Voice to Text (`com.offlineinc.voicetotext`).

To reinstall a stock app, download its APK from the official releases and `adb install` it (each
package is a single APK, so no split-APK tooling is needed):

| App               | Package                           | Releases                                                                                   |
| ----------------- | --------------------------------- | ------------------------------------------------------------------------------------------ |
| DumbDown Launcher | `com.offlineinc.dumbdownlauncher` | [Offline-DC/dumb-down-launcher](https://github.com/Offline-DC/dumb-down-launcher/releases) |
| DumbTT9           | `com.offlineinc.dumbtt9`          | [Offline-DC/dumbtt9](https://github.com/Offline-DC/dumbtt9/releases)                       |
| Voice to Text     | `com.offlineinc.voicetotext`      | [Offline-DC/voicetotextapp](https://github.com/Offline-DC/voicetotextapp/releases)         |

Each release lists its `sha256`; check it with `shasum -a 256` before installing. Releases marked
*Pre-release* are beta builds, so take the one tagged *Latest* unless you want the beta channel.

</details>

<details>
<summary><b>Magisk modules</b></summary>

### Launcher module: `magisk/launcher/`

Nothing is patched inside the APK. The launcher already knows how to be "unlocked and
activated" — it writes those values itself when pairing succeeds, or when you tap *skip pairing*
/ *skip setup*. This module just writes them for you.

At every boot, `[service.sh](magisk/launcher/service.sh)` waits for the phone to be unlocked
(the launcher's settings aren't readable before that), then sets six switches in its
SharedPreferences:

- `has_subscription=true`, `hide_smart_txt=false`, `hide_audio_bundle=false` — every tile
becomes openable. To hide tiles you don't use, see the LSPosed module below.
- `is_paired=true`, `device_registered=true`, `setup_skipped=true` — the launcher considers
activation done, so it boots straight to the home grid instead of the SIM-check screen.

If anything changed, it force-stops the launcher so it reloads the new values. It only edits
files that already exist and only rewrites when the contents actually differ, so repeated boots
are harmless.

The second half is `[system/etc/hosts](magisk/launcher/system/etc/hosts)`, which points the dead
backend at `127.0.0.1`. The launcher keeps retrying that server in the background, and when the
request fails outright it takes an error path that leaves the existing settings untouched. That
guarantees nothing can ever re-lock your tiles, even if the host comes back one day.

A log of what the module did each boot lands in `/data/local/tmp/dumbdown_plan_unlock.log`.

#### Install

```bash
cd magisk/launcher && zip -r ../../launcher-module.zip . && cd ../..
adb push launcher-module.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/launcher-module.zip'
adb reboot
```

If Magisk only extracts `module.prop` (it has done this on this device), copy `service.sh` and
`system/etc/hosts` into `/data/adb/modules/dumbdown_plan_unlock/` by hand and
`chmod 0644 service.sh`.

#### Revert

Remove the module in the Magisk app, or delete `/data/adb/modules/dumbdown_plan_unlock`, then
reboot. The launcher keeps whatever settings were last written; delete
`/data/data/com.offlineinc.dumbdownlauncher/shared_prefs/device_pairing.xml` for a clean slate.

### LSPosed status notification module: `magisk/lsposed-notif/`

Once LSPosed is installed (see microG support below) it posts a permanent, unswipeable
**LSPosed loaded** notification. It's meant as the entry point to the parasitic manager, but on
this phone the manager is already a real app, so the notification is pure noise. The manager has
a *Status Notification* toggle to turn it off — except it's greyed out until you first create a
launcher shortcut, and DumbDown Launcher (`com.flipos.launcher`) can't pin shortcuts, so you're
stuck.

Fighting it at the Android layer is a dead end: the notification lives on the `lsposed_status`
channel owned by the `android` package, which this ROM marks non-blockable (so its Settings
toggle is greyed too), and LSPosed re-asserts the channel at `IMPORTANCE_MIN` on every boot —
overriding even a user-locked `IMPORTANCE_NONE`.

The toggle just writes one value, though. LSPosed keeps its framework prefs in the SQLite DB
`/data/adb/lspd/config/modules_config.db`, and reads `enable_status_notification` at
`BOOT_COMPLETED` (`LSPosedService.dispatchBootCompleted`): when it's `false` it *cancels* the
notification instead of posting it. So this module just sets that value. At `post-fs-data` (before
`system_server` starts, so the DB is not open by anyone)
[`post-fs-data.sh`](magisk/lsposed-notif/post-fs-data.sh) runs a small helper that writes the row
`('lspd', 0, 'config', 'enable_status_notification', <Boolean.FALSE>)`. The value is a serialized
`java.lang.Boolean` (LSPosed stores config via Java `ObjectOutputStream`), so the helper writes
the exact bytes `writeObject(Boolean.FALSE)` produces. It's idempotent, and re-applies the setting
if LSPosed is ever reinstalled or its config reset. The scope-request and module-update
notifications are left alone.

There is no `sqlite3` on the device and no way to hand-edit a SQLite b-tree safely, so the writer
is a tiny native binary, [`lspd_status_notif`](magisk/lsposed-notif/lspd_status_notif), built from
[`src/lspd_status_notif.c`](magisk/lsposed-notif/src/lspd_status_notif.c) linked against the
official SQLite amalgamation. It's a fully static `armeabi-v7a` musl build, which runs on Android
via the kernel syscall ABI without needing bionic. Rebuild it with [zig](https://ziglang.org)
(no Android NDK required):

```bash
curl -sO https://www.sqlite.org/2024/sqlite-amalgamation-3470200.zip
unzip -q sqlite-amalgamation-3470200.zip
zig cc -target arm-linux-musleabihf -O2 -static -s \
  -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_DQS=0 \
  -I sqlite-amalgamation-3470200 \
  magisk/lsposed-notif/src/lspd_status_notif.c sqlite-amalgamation-3470200/sqlite3.c \
  -o magisk/lsposed-notif/lspd_status_notif
```

The binary takes an optional argument: `lspd_status_notif 0` disables the notification (default),
`lspd_status_notif 1` re-enables it, and an optional second argument overrides the DB path.

#### Install

```bash
cd magisk/lsposed-notif && zip -r ../../lsposed-notif-module.zip . && cd ../..
adb push lsposed-notif-module.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/lsposed-notif-module.zip'
adb reboot
```

Like the launcher module, Magisk on this device sometimes stages only `module.prop` on the first
`--install-module` and finalizes the rest on reboot; if the module folder under
`/data/adb/modules/lsposed_notif_off/` is missing `post-fs-data.sh` or `lspd_status_notif` after
that, copy them in by hand and `chmod 0755 lspd_status_notif`. A log of each boot's result lands in
`/data/local/tmp/lsposed_notif_off.log`.

#### Revert

Remove the module in the Magisk app (or delete `/data/adb/modules/lsposed_notif_off` and reboot).
The `enable_status_notification=false` value stays in LSPosed's config after removal, so to bring
the notification back either run `lspd_status_notif 1` once or toggle *Status Notification* back on
in the manager.

</details>

<details>
<summary><b>microG support</b></summary>

The phone ships with no Google code at all — no Play Services, no Play Store, no Services
Framework. That is a feature until you sideload something that expects them: Uber Lite wants
Maps, and anything built on Firebase has no route for push notifications.

[microG](https://microg.org) fills that gap, but it has to be convincing. Apps don't just look
for the package name `com.google.android.gms` — the Play Services client library checks the
signing certificate too, and microG is signed by the NOGAPPS Project. So the system has to be
persuaded to report Google's certificate on microG's behalf. That is *signature spoofing*, and
most custom ROMs have it built in. Stock TCL does not, so it gets bolted on with Xposed.

Two halves, then: get microG in as a privileged system app, then make the framework lie about
its signature. Nothing here lives in this repo; it's all upstream downloads.

| Piece                            | From (GitHub)  | Why                                                   |
| -------------------------------- | -------------- | ----------------------------------------------------- |
| GmsCore + Companion              | `microg`       | microG itself, plus a `com.android.vending` stub.     |
| `microg_installer_revived_again` | `spacealtctrl` | Moves both into `priv-app` with the right allowlists. |
| LSPosed **v1.11.0**              | `JingMatrix`   | Xposed framework — see the version warning below.     |
| FakeGApps 6.6                    | `whew-inc`     | The hook that actually does the spoofing.             |

### Install microG as a system app

Check what you downloaded before installing it — microG's GitHub releases have a history of
being served truncated, and a corrupt APK here fails in confusing ways:

```bash
unzip -p com.google.android.gms-*.apk META-INF/CERT.RSA \
  | openssl pkcs7 -inform DER -print_certs -outform PEM \
  | openssl x509 -noout -subject -fingerprint -sha256
# O=NOGAPPS Project, 9B:D0:67:27:...:95:D1:41:65
```

That fingerprint is the same key microG publishes for its F-Droid repo, and the Companion APK is
signed with it too.

Install both as ordinary user apps **first**. This isn't optional — the module's `customize.sh`
promotes whatever it finds already installed, so with nothing to find it installs a do-nothing
module and cheerfully reports success.

```bash
adb install com.google.android.gms-250932030.apk    # ~101 MB, takes a while
adb install com.android.vending-84022630.apk
adb push microg_installer_revived_again-v1.1.2.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/microg_installer_revived_again-v1.1.2.zip'
adb reboot
```

The module copies both APKs to `system/product/priv-app/`, which Magisk maps onto the real
`/product` without touching the original partition, and adds the supporting files microG
needs as a privileged app: `privapp-permissions-*.xml` allowlists, GsfProxy for old apps that
still ask for `com.google.android.gsf`, Maps v1, and a sysconfig file exempting microG from
power-save so push survives Doze.

After the reboot:

```bash
adb shell su -c 'pm dump com.google.android.gms' | grep -E 'flags=|privateFlags='
```

You want `SYSTEM … UPDATED_SYSTEM_APP` and `PRIVILEGED PRODUCT`. `codePath` still points into
`/data/app` and that's correct — the copy you installed by hand counts as an update to the
system app and keeps the privileged flags.

This ROM sets `ro.control_privapp_permissions=enforce`, which on some builds bootloops when a
privileged app requests a permission that isn't allowlisted. It's a `user` build, so a mismatch
only logs a warning. Worth knowing before you edit those XMLs.

### Add signature spoofing

Zygisk first. Nested quotes get mangled on the way through `su -c`, so
[`enable_zygisk.sh`](microg/enable_zygisk.sh) wraps the statement in a file rather than fighting
the escaping — push it and run it:

```bash
adb push microg/enable_zygisk.sh /data/local/tmp/
adb shell su -c 'sh /data/local/tmp/enable_zygisk.sh'
adb reboot
```

**Use LSPosed v1.11.0, not Vector.** JingMatrix renamed the fork to Vector at v2.0. Vector v2.1
installs fine and the manager even reports "Activated", but it throws
`NoClassDefFoundError: xposed.dummy.XResourcesSuperClass` on every process fork and never loads
a single module — spoofing just silently doesn't happen, with no obvious symptom except a failing
self-check. v1.11.0 is the last LSPosed-branded release and works.

```bash
adb push LSPosed-v1.11.0-7209-zygisk-release.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/LSPosed-v1.11.0-7209-zygisk-release.zip'
adb install app-release.apk      # FakeGApps 6.6
adb reboot
```

LSPosed normally opens its manager from a notification, using a parasitic manager that avoids
installing a visible app. On this phone that notification dies inside MediaTek's
`AsyncDrawableCache`, and the `*#*#5776733#*#*` secret code does nothing. Install the manager
out of the module zip instead:

```bash
unzip -j LSPosed-v1.11.0-7209-zygisk-release.zip manager.apk
adb install manager.apk
adb shell am start -n org.lsposed.manager/.ui.activity.MainActivity
```

In the manager: **Modules → FakeGapps → Enable module**, then tick **System Framework** in the
scope list underneath. Nothing else — the hook belongs in `system_server`, not in individual
apps. Reboot.

There's no touchscreen, but injected taps still work. Read coordinates out of the view tree
rather than guessing from screenshots:

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml   # grep for bounds=
adb shell input tap 120 188
```

### Verify

The module log tells you whether the hook is live at all:

```bash
adb shell su -c 'cat /data/adb/lspd/log/modules_*.log'
```

It should mention `Loading legacy module inc.whew.android.fakegapps` and
`Loading class inc.whew.android.fakegapps.FakeSignatures`. A log with nothing but the watchdog
header means no module ran and nothing is being spoofed — that's the Vector failure above.

Then microG's own self-check, which is the authoritative answer:

```bash
adb shell am start -n com.google.android.gms/org.microg.gms.ui.SettingsActivity
```

Open *Self-Check*; the first row, "System spoofs signature", has to be ticked.

Grant location by hand. The module ships a `default-permissions` file, but that only applies to
apps installed after it, and microG went on first:

```bash
for p in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  adb shell su -c "pm grant com.google.android.gms android.permission.$p"
done
```

Two things that surprise people afterwards. DumbDown Launcher won't show microG or the LSPosed
manager, so `am start` is the only way back into either. And push notifications still won't
arrive until you enable Device Registration and Cloud Messaging in microG's settings — that
registers the phone with Google, so it's deliberately off until you choose it.

### Revert

Remove `microg_installer_revived_again` and `zygisk_lsposed` in the Magisk app, or delete their
directories under `/data/adb/modules/` and reboot. Then clear the user-installed halves:

```bash
adb uninstall com.google.android.gms
adb uninstall com.android.vending
adb uninstall inc.whew.android.fakegapps
adb uninstall org.lsposed.manager
```

Set `zygisk` back to `0` the same way you set it, if nothing else needs it. Neither module ever
writes to the real `/system` or `/product`, so pulling them restores the stock partitions
exactly.

</details>

<details>
<summary><b>Show and hide launcher apps (LSPosed)</b></summary>

### Unhiding denylisted apps

DumbDown Launcher's app drawer lists every app with a launcher icon, minus a hardcoded set of 28
package names in `AllAppsActivity` — Magisk, Chrome, the stock dialer and contacts (replaced by
the launcher's own screens), OpenBubbles, a pile of MediaTek and carrier tools, and the launcher
itself. The home grid is built from the drawer's list, so an app on that set can't be pinned
either. Because the list is compiled into the app, no settings change can lift it.

The set is an ordinary Java `Set` that the launcher re-reads every time it rebuilds the drawer,
though. [`lsposed/unhide-apps/`](lsposed/unhide-apps) is a tiny LSPosed module that runs inside
the launcher's process at startup and removes entries from it.
[`UnhideHook.java`](lsposed/unhide-apps/src/com/amber/dumbdownunhide/UnhideHook.java) finds the
set by content rather than by its obfuscated field name (`z0` in the current build), so a
launcher update that renames fields doesn't break it. The packages it unhides are the `UNHIDE`
array at the top of that file: `com.topjohnwu.magisk`, `com.android.chrome` and
`org.chromium.chrome`. Add more there and rebuild.

### Hiding tiles

Going the other way, adding entries to that set only works for real installed apps. Most of the
launcher's tiles are virtual — built-in screens with IDs like `__QUACK__` that it appends to the
list *after* the denylist has been applied. So the module hides tiles one step later instead.
Every tile list the launcher builds (the drawer, and the default home grid) ends with the same
subscription-lock pass (`bc.f1.a` in v6.51.0), and the hook drops hidden tiles from what it
returns. One background task (`bc.k`) bypasses that: it adds tiles (quack, contact sync) straight into the open
drawer's on-screen list and into the drawer cache that the home grid reads. So after each run the
hook removes hidden tiles from both — from the on-screen list in place, since the drawer is
watching that exact list object.

The hidden tiles are the `HIDE` set in `UnhideHook.java`, matched on each tile's ID:

| Tile          | ID                                                                  |
| ------------- | ------------------------------------------------------------------- |
| dumb keys     | `com.offlineinc.dumbtt9` (the DumbTT9 keyboard's settings app)      |
| 2-factor auth | `__TWO_FACTOR__`                                                    |
| device setup  | `__DEVICE_SETUP__`                                                  |
| quack         | `__QUACK__`                                                         |
| rideshare     | `__DUMB_RIDE__`                                                     |
| signal        | `__DUMB_SIGNAL__` (the launcher's own client, not Signal's app)     |
| smart cal     | `__CALENDAR__`                                                      |
| free up space | `__FREE_UP_SPACE__`                                                 |
| smart txt     | `com.openbubbles.messaging` or `__GOOGLE_MESSAGES__`, per platform  |
| contact sync  | `__CONTACT_SYNC__`                                                  |

Hiding Dumb Keys only hides its settings tile; the keyboard keeps working. Its settings are still
reachable with
`adb shell am start -n com.offlineinc.dumbtt9/io.github.sspanak.tt9.preferences.PreferencesActivity`.

The same passes also relabel tiles. `RENAME` in `UnhideHook.java` maps a tile ID to the label to
show instead; it currently renames smart music (`__DUMB_SPOTIFY__`, which is the launcher's
Spotify client) to `spotify`. The launcher sorts the drawer after this, so a renamed tile lands
in its new alphabetical spot.

Those class names are obfuscated, and every launcher update renames them (v6.42.0 called them
`bc.g1` and `bc.j`). So the module doesn't look them up by name. Instead,
[`DexScanner.java`](lsposed/unhide-apps/src/com/amber/dumbdownunhide/DexScanner.java) reads the
launcher's own dex files and finds the methods by the string constants they use, which
obfuscation leaves alone:

- The filter is the static `ArrayList -> ArrayList` method that reads the `has_subscription`,
  `hide_audio_bundle` and `hide_smart_txt` preferences.
- The injector is the coroutine body that loads both `__CONTACT_SYNC__` and `__QUACK__`.

Each tile's ID and label are read from the fields that its `toString()` prints as `packageName=`
and `label=`, so no field names are hardcoded either. The scan takes about a second on the phone, so its result is cached in
the launcher's `cache/dumbdown-unhide.properties` and only redone when the launcher APK changes.
If a future update changes the strings themselves, the module logs it (see Verify) rather than
crashing the launcher.

Nothing in the APK on the phone changes, and the module needs LSPosed, set up as in microG
support above.

### Build

[`build.sh`](lsposed/unhide-apps/build.sh) uses only the Android SDK (build-tools 36.0.0,
platform android-36) and a JDK 11+. No Gradle. The Xposed API classes it compiles against are
small stubs in `stubs/` that are never put in the APK, since LSPosed provides the real ones. It
signs with your `~/.android/debug.keystore`. A prebuilt `dumbdown-unhide.apk` is committed.

```bash
lsposed/unhide-apps/build.sh
```

### Install

```bash
adb install lsposed/unhide-apps/dumbdown-unhide.apk
adb shell am start -n org.lsposed.manager/.ui.activity.MainActivity
```

In the manager: **Modules → DumbDown App Visibility → Enable module**, with only **DumbDown
Launcher** ticked in the scope list. Then restart the launcher (a reboot works too):

```bash
adb shell am force-stop com.offlineinc.dumbdownlauncher
```

Magisk and Chrome now show in the app drawer and can be added to the home grid, and the hidden
tiles are gone from both. To update after editing `UNHIDE`, `HIDE` or `RENAME`, rebuild, run
`adb install -r` with the new APK, and force-stop the launcher again — LSPosed keeps the module
enabled and scoped across updates.

### Verify

```bash
adb shell su -c 'cat /data/adb/lspd/log/modules_*.log' | grep DumbDownUnhide
```

You want three lines when the launcher starts, and two more once the tile lists have been built:

- `unhid [com.topjohnwu.magisk, ...] in place`
- `hiding tiles via bc.f1.a`
- `scrubbing drawer after bc.k.invokeSuspend`
- `renamed tile __DUMB_SPOTIFY__ from "smart music" to "spotify"` (logged once per launcher start)
- `hid tiles [...]` (logged once per launcher start)

The class names in the middle two lines change with each launcher build. On the first start after
a launcher update, there's also `scanned launcher dex in N ms` before them.

Any line containing `not found` or `ambiguous` means a launcher update changed the code that step
looks for, so its fingerprint in `UnhideHook.java` needs updating. The other steps keep working
regardless.

### Revert

Disable the module in the LSPosed manager (or `adb uninstall com.amber.dumbdownunhide`) and
force-stop the launcher again.

</details>
