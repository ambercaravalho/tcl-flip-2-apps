# tcl-flip-2-apps

Fixes for the TCL Flip 2 / TCL Flip Go, packaged as two Magisk modules. Also keeps a copy of
the phone's stock offlineinc apps so they can be reinstalled if you remove or break one, and
notes on getting microG onto the phone, which needs no code from this repo but is fiddly enough
to be worth writing down.

| Path               | What it is                                                     |
| ------------------ | -------------------------------------------------------------- |
| `magisk/launcher/` | Unlocks the app tiles and skips the failing activation screen. |
| `magisk/bootanim/` | Swaps the stock TCL boot animation for the offlineinc one.     |
| `apks/`            | The three stock offlineinc APKs pulled off the device.         |

<details>
<summary><b>The phone</b></summary>

The TCL Flip 2 (model `4058G`) and the TCL Flip Go are cheap flip phones with a 240x320 screen.
They look like feature phones but run Android 11 (SDK 30), and the home screen is **DumbDown
Launcher** (`com.offlineinc.dumbdownlauncher`) — a locked-down launcher that only shows the apps
your plan is supposed to include. Two companion apps ship with it: DumbTT9
(`com.offlineinc.dumbtt9`, the T9 keyboard) and Voice to Text (`com.offlineinc.voicetotext`).

Reinstall a stock app with `adb install apks/com.offlineinc.dumbdownlauncher.apk` (each package
is a single APK, so no split-APK tooling is needed).

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
becomes openable.
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

### Boot animation module: `magisk/bootanim/`

The launcher APK ships an offlineinc boot animation in its assets that the phone never plays —
240x320 at 12fps, which is an exact match for this panel. This module makes it the real boot
animation.

The stock file lives at `/product/media/bootanimation.zip`. The module ships its replacement at
`system/product/media/bootanimation.zip`; Magisk maps `system/product` onto the real `/product`,
so the original file is never overwritten and removing the module brings it straight back.

#### Install

```bash
cd magisk/bootanim && zip -r ../../bootanim-module.zip . && cd ../..
adb push bootanim-module.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/bootanim-module.zip'
adb reboot
```

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
`/product` exactly like the boot animation module does, and adds the supporting files microG
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
