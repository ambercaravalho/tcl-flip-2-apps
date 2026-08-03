# tcl-flip-2-apps

Fixes for the TCL Flip 2 / TCL Flip Go, packaged as two Magisk modules. Also keeps a copy of
the phone's stock offlineinc apps so they can be reinstalled if you remove or break one.

## The phone

The TCL Flip 2 (model `4058G`) and the TCL Flip Go are cheap flip phones with a 240x320 screen.
They look like feature phones but run Android 11 (SDK 30), and the home screen is **DumbDown
Launcher** (`com.offlineinc.dumbdownlauncher`) — a locked-down launcher that only shows the apps
your plan is supposed to include. Two companion apps ship with it: DumbTT9
(`com.offlineinc.dumbtt9`, the T9 keyboard) and Voice to Text (`com.offlineinc.voicetotext`).

The launcher checks a vendor backend (`offline-dc-backend-ba4815b2bcc8.herokuapp.com`) to find
out which plan the phone is on. **That server is gone**, so on a stock device two things break
permanently and there is no way to fix them from the UI:

- Tapping most app tiles shows **"This feature isn't on your plan."**
- Boot can hang on an activation screen — "waiting for ur sim…", "activating ur phone…" — that
  always ends in **"sorry, we couldn't connect to the network."**

The two modules below fix exactly those two things. Both need root (Magisk).

| Path | What it is |
| --- | --- |
| `magisk/launcher/` | Unlocks the app tiles and skips the failing activation screen. |
| `magisk/bootanim/` | Swaps the stock TCL boot animation for the offlineinc one. |
| `apks/` | The three stock offlineinc APKs pulled off the device. |

Reinstall a stock app with `adb install apks/com.offlineinc.dumbdownlauncher.apk` (each package
is a single APK, so no split-APK tooling is needed).

## Launcher module: `magisk/launcher/`

Nothing is patched inside the APK. The launcher already knows how to be "unlocked and
activated" — it writes those values itself when pairing succeeds, or when you tap *skip pairing*
/ *skip setup*. This module just writes them for you, since the server that would normally do it
is dead.

At every boot, [`service.sh`](magisk/launcher/service.sh) waits for the phone to be unlocked
(the launcher's settings aren't readable before that), then sets six switches in its
SharedPreferences:

- `has_subscription=true`, `hide_smart_txt=false`, `hide_audio_bundle=false` — every tile
  becomes openable.
- `is_paired=true`, `device_registered=true`, `setup_skipped=true` — the launcher considers
  activation done, so it boots straight to the home grid instead of the SIM-check screen.

If anything changed, it force-stops the launcher so it reloads the new values. It only edits
files that already exist and only rewrites when the contents actually differ, so repeated boots
are harmless.

The second half is [`system/etc/hosts`](magisk/launcher/system/etc/hosts), which points the dead
backend at `127.0.0.1`. The launcher keeps retrying that server in the background, and when the
request fails outright it takes an error path that leaves the existing settings untouched. That
guarantees nothing can ever re-lock your tiles, even if the host comes back one day.

A log of what the module did each boot lands in `/data/local/tmp/dumbdown_plan_unlock.log`.

### Install

```bash
cd magisk/launcher && zip -r ../../launcher-module.zip . && cd ../..
adb push launcher-module.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/launcher-module.zip'
adb reboot
```

If Magisk only extracts `module.prop` (it has done this on this device), copy `service.sh` and
`system/etc/hosts` into `/data/adb/modules/dumbdown_plan_unlock/` by hand and
`chmod 0644 service.sh`.

### Revert

Remove the module in the Magisk app, or delete `/data/adb/modules/dumbdown_plan_unlock`, then
reboot. The launcher keeps whatever settings were last written; delete
`/data/data/com.offlineinc.dumbdownlauncher/shared_prefs/device_pairing.xml` for a clean slate.

## Boot animation module: `magisk/bootanim/`

The launcher APK ships an offlineinc boot animation in its assets that the phone never plays —
240x320 at 12fps, which is an exact match for this panel. This module makes it the real boot
animation.

The stock file lives at `/product/media/bootanimation.zip`. The module ships its replacement at
`system/product/media/bootanimation.zip`; Magisk maps `system/product` onto the real `/product`,
so the original file is never overwritten and removing the module brings it straight back.

### Install

```bash
cd magisk/bootanim && zip -r ../../bootanim-module.zip . && cd ../..
adb push bootanim-module.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/bootanim-module.zip'
adb reboot
```
