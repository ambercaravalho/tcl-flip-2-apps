# tcl-flip-2-apps

Fixes for the TCL Flip 2 / TCL Flip Go, a flip phone running Android 11 with the locked-down
**DumbDown Launcher**. Everything here needs a rooted phone with Magisk. The LSPosed modules also
need LSPosed, which is set up in [microG and LSPosed](#microg-and-lsposed).

| Module                  | Type    | What it does                                                                     |
| ----------------------- | ------- | -------------------------------------------------------------------------------- |
| `magisk/launcher/`      | Magisk  | Unlocks every launcher tile, skips activation, keeps the browser enabled.        |
| `magisk/lsposed-notif/` | Magisk  | Removes the permanent "LSPosed loaded" notification.                             |
| `magisk/webview/`       | Magisk  | Lets apps use Google's up-to-date WebView instead of the stock one from 2020.    |
| `lsposed/unhide-apps/`  | LSPosed | Shows Magisk and the browser, hides unused tiles, adds cursor apps.              |
| `lsposed/tcl-tweaks/`   | LSPosed | Keeps Settings and the browser light, drops the "fully charged" notification.    |

## Installing

**Magisk modules.** Zip the module folder, install it with Magisk and reboot:

```bash
cd magisk/launcher && zip -r ../../module.zip . && cd ../..
adb push module.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/module.zip'
adb reboot
```

To remove one, delete it in the Magisk app and reboot.

**LSPosed modules.** Install the prebuilt APK, then open the LSPosed manager:

```bash
adb install lsposed/unhide-apps/dumbdown-unhide.apk
adb shell am start -n org.lsposed.manager/.ui.activity.MainActivity
```

In **Modules**, enable the module and tick the apps listed under its scope below. Then restart
those apps or reboot. To remove one, disable it in the manager or uninstall it.

**Rebuilding.** The prebuilt APKs and binaries are committed. Each module with source code has a
`build.sh` that only needs the Android SDK (build-tools 36, platform 36) and a JDK 11+, and signs
with your debug keystore. The LSPosed notification helper is C, built with [zig](https://ziglang.org):

```bash
curl -sO https://www.sqlite.org/2024/sqlite-amalgamation-3470200.zip && unzip -q sqlite-amalgamation-3470200.zip
zig cc -target arm-linux-musleabihf -O2 -static -s \
  -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_DQS=0 \
  -I sqlite-amalgamation-3470200 magisk/lsposed-notif/src/lspd_status_notif.c \
  sqlite-amalgamation-3470200/sqlite3.c -o magisk/lsposed-notif/lspd_status_notif
```

## Magisk modules

### Launcher unlock: `magisk/launcher/`

The launcher locks most of its tiles behind a subscription and a pairing step whose server no
longer exists. At every boot, this module writes the launcher's own settings so it believes the
subscription is active and setup is done. Every tile opens, and the phone boots straight to the
home screen. It also blocks the dead server, so nothing can re-lock the tiles later.

Since v6.50.0 the launcher also disables the browser once. The module stops that from happening,
and re-enables the browser at the next boot if it was disabled anyway.

Log: `/data/local/tmp/dumbdown_plan_unlock.log`. Removing the module leaves the last settings in
place; delete `/data/data/com.offlineinc.dumbdownlauncher/shared_prefs/device_pairing.xml` for a
clean start.

### LSPosed notification off: `magisk/lsposed-notif/`

LSPosed posts a permanent "LSPosed loaded" notification. The manager has a switch to turn it off,
but on this phone the switch is greyed out. This module sets that switch directly in LSPosed's
settings at boot. Other LSPosed notifications are unaffected.

Log: `/data/local/tmp/lsposed_notif_off.log`. The setting stays after you remove the module. To get
the notification back, run `lspd_status_notif 1` once, or turn it on again in the manager.

### Google WebView: `magisk/webview/`

Apps that show web pages use the system WebView. The phone's is version 83 from 2020, which some
sites refuse; Proton Mail's settings pages, for example, say the browser is out of date. The stock
WebView can't be updated, so this module lets Google's **Android System WebView** take over. It
doesn't need Google Play services, and the stock WebView stays as the fallback.

1. Install Android System WebView from Aurora Store. Aurora's search only shows the beta, dev and
   canary versions, so open the stable one directly:

   ```bash
   adb shell am start -a android.intent.action.VIEW -d 'market://details?id=com.google.android.webview' com.aurora.store
   ```

2. Install this module and reboot.
3. Check: `adb shell dumpsys webviewupdate | grep Current` should name `com.google.android.webview`.

Aurora Store keeps it updated from then on. Removing the module switches back to the stock WebView.

## LSPosed modules

### DumbDown App Visibility: `lsposed/unhide-apps/`

**Scope:** DumbDown Launcher.

The launcher decides what appears in its app drawer and home grid, and which apps get the
on-screen cursor. This module changes those choices. Each one is a list at the top of
[`UnhideHook.java`](lsposed/unhide-apps/src/com/amber/dumbdownunhide/UnhideHook.java). Edit the
lists, rebuild, reinstall and restart the launcher.

- **`UNHIDE`**: apps the launcher deliberately hides, to show anyway. Currently Magisk, Chrome and
  Chromium.
- **`HIDE`**: tiles to hide. Currently dumb keys, 2-factor auth, device setup, rideshare, signal
  (the launcher's own client, not the Signal app), smart cal, free up space, smart txt and contact
  sync. Hiding dumb keys only hides its settings tile; the keyboard keeps working.
- **`RENAME`**: tiles to relabel. Currently smart music becomes "spotify".
- **`MOUSE_APPS`**: apps that turn the cursor on when opened from the launcher, like the browser
  does. Currently Aurora Store, Immich, K-9 Mail, LSPosed, Lyft, Magisk, My Tello, Pebble, Proton
  Mail, Signal and Telegram.

The launcher's code is obfuscated and changes with every update, so the module finds what it needs
by content rather than by name, which usually survives updates. If an update does break a step,
the log says `not found`, and the other steps keep working:

```bash
adb shell su -c 'cat /data/adb/lspd/log/modules_*.log' | grep DumbDownUnhide
```

### TCL Tweaks: `lsposed/tcl-tweaks/`

**Scope:** Settings, System UI and Browser. Untick an app to drop its fix.

- **Settings stays in light mode.** TCL's dark mode for Settings is only half done, leaving black
  text on dark backgrounds, so Settings stays light while the rest of the phone is dark.
- **The browser stays in light mode.** Its dark mode has the same problem, so the module sets the
  browser's own Theme setting to Light every time it starts.
- **No "Battery is fully charged" notification.** Low-battery and battery saver warnings still
  show.

Log: `adb shell su -c 'cat /data/adb/lspd/log/modules_*.log' | grep TclTweaks`. This module
replaces the older Settings Light Mode module (`com.amber.settingslight`); uninstall that one if
you have it.

## Troubleshooting

**Magisk only installed `module.prop`.** This happens on this phone now and then. Copy the rest of
the module's files into `/data/adb/modules/<module id>/` by hand (the id is in `module.prop`), run
`chmod 0755` on any binaries, and reboot.

**The cursor stopped working after restarting the launcher.** Force-stopping the launcher kills its
cursor service, and Android doesn't restart it. Reboot, or:

```bash
adb shell su -c 'settings delete secure enabled_accessibility_services && settings put secure enabled_accessibility_services com.offlineinc.dumbdownlauncher/com.offlineinc.dumbdownlauncher.MouseAccessibilityService && settings put secure accessibility_enabled 1'
```

**Web pages draw partly or entirely white.** Logos, icons or even text go missing. The phone's GPU
driver sometimes chokes on cached graphics data, and clearing the cache fixes it. For the browser:

```bash
adb shell am force-stop org.chromium.chrome
adb shell su -c 'cd /data/data/org.chromium.chrome/app_chrome && rm -rf ShaderCache GrShaderCache GraphiteDawnCache Default/GPUCache Default/DawnCache'
```

For web pages inside another app, force-stop that app and delete
`/data/data/<app package>/app_webview/Default/GPUCache`.

**Tapping through screens over adb.** There's no touchscreen, but injected taps work. Find
coordinates with `adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`
(look for `bounds=`), then `adb shell input tap X Y`.

## microG and LSPosed

<details>
<summary><b>Setup steps</b></summary>

The phone has no Google services, so apps that need them (Uber Lite, push notifications) don't
work. [microG](https://microg.org) replaces them, but it needs *signature spoofing*, which the stock
ROM lacks. LSPosed with the FakeGApps module adds it. Nothing here is in this repo; download:

- **GmsCore** and **Companion** from `microg` on GitHub
- **`microg_installer_revived_again`** from `spacealtctrl`
- **LSPosed v1.11.0** from `JingMatrix`. Not Vector: Vector v2.x says "Activated" but never loads
  any modules.
- **FakeGApps 6.6** from `whew-inc`

**1. Install microG as a system app.** First check the download wasn't truncated (microG's releases
have a history of that). The signer should be `O=NOGAPPS Project`:

```bash
unzip -p com.google.android.gms-*.apk META-INF/CERT.RSA \
  | openssl pkcs7 -inform DER -print_certs | openssl x509 -noout -subject
```

Install both APKs as normal apps *before* the module, because the module only promotes what's
already installed:

```bash
adb install com.google.android.gms-250932030.apk
adb install com.android.vending-84022630.apk
adb push microg_installer_revived_again-v1.1.2.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/microg_installer_revived_again-v1.1.2.zip'
adb reboot
```

**2. Enable Zygisk, then install LSPosed and FakeGApps:**

```bash
adb push microg/enable_zygisk.sh /data/local/tmp/
adb shell su -c 'sh /data/local/tmp/enable_zygisk.sh'
adb reboot
adb push LSPosed-v1.11.0-7209-zygisk-release.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/LSPosed-v1.11.0-7209-zygisk-release.zip'
adb install app-release.apk      # FakeGApps
adb reboot
```

LSPosed's usual way into its manager (a notification) doesn't work on this phone, so install the
manager from the LSPosed zip:

```bash
unzip -j LSPosed-v1.11.0-7209-zygisk-release.zip manager.apk && adb install manager.apk
adb shell am start -n org.lsposed.manager/.ui.activity.MainActivity
```

In the manager, enable **FakeGapps** with only **System Framework** ticked, and reboot.

**3. Check and finish.** Open microG's settings; under *Self-Check*, "System spoofs signature" must
be ticked:

```bash
adb shell am start -n com.google.android.gms/org.microg.gms.ui.SettingsActivity
```

Grant microG location access, which it doesn't get automatically:

```bash
for p in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  adb shell su -c "pm grant com.google.android.gms android.permission.$p"
done
```

For push notifications, turn on Device Registration and Cloud Messaging in microG's settings. The
launcher doesn't show microG or the LSPosed manager, so open them with the `am start` commands
above.

**Removing it:** delete `microg_installer_revived_again` and `zygisk_lsposed` in Magisk, reboot,
then `adb uninstall` `com.google.android.gms`, `com.android.vending`,
`inc.whew.android.fakegapps` and `org.lsposed.manager`.

</details>

## Stock apps

To reinstall one of the phone's own apps, download it from the official releases and
`adb install` it. Each release lists a `sha256` to check. Take the release tagged *Latest*, not a
*Pre-release*.

| App               | Releases                                                                                   |
| ----------------- | ------------------------------------------------------------------------------------------ |
| DumbDown Launcher | [Offline-DC/dumb-down-launcher](https://github.com/Offline-DC/dumb-down-launcher/releases) |
| DumbTT9 keyboard  | [Offline-DC/dumbtt9](https://github.com/Offline-DC/dumbtt9/releases)                       |
| Voice to Text     | [Offline-DC/voicetotextapp](https://github.com/Offline-DC/voicetotextapp/releases)         |
