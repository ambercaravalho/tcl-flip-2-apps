# tcl-flip-2-apps

Notes, APKs, and tooling from reverse-engineering and re-customizing my TCL Flip 2
(model `4058G`, Android 11 / SDK 30, rooted with Magisk).

The phone runs a locked-down home screen from a shuttered business, **DumbDown Launcher**
(`com.offlineinc.dumbdownlauncher`), plus two companion apps I also built. This repo is
where I figure out how that launcher works and undo the parts that depend on a backend
that no longer exists. It is not a full-device backup; only the three offlineinc apps and
their analysis live here.

## Layout

| Path | What it is |
| --- | --- |
| `apks/` | The three offlineinc APKs pulled off the device (Git LFS). |
| `decomp/` | jadx output for `com.offlineinc.dumbdownlauncher` — the reference for the findings below. |
| `magisk/` | A Magisk module that re-enables the plan-gated features without touching app code. |

### The apps

| Package | App | Notes |
| --- | --- | --- |
| `com.offlineinc.dumbdownlauncher` | DumbDown Launcher | The `HOME` launcher; runs an accessibility service that polices other apps. |
| `com.offlineinc.dumbtt9` | DumbTT9 | T9 keyboard companion. |
| `com.offlineinc.voicetotext` | Voice to Text | Dictation companion. |

## The "This feature isn't on your plan" gate

DumbDown Launcher decides which app tiles you can open based on three booleans stored in its
`device_pairing` SharedPreferences. Those booleans were fetched from a backend that paired
each device to a subscription plan. That backend
(`offline-dc-backend-ba4815b2bcc8.herokuapp.com`) is gone, so the flags never get set to
their "paid" values and paid tiles show "This feature isn't on your plan."

### Where the decision is made

[`decomp/.../za/i0.java`](decomp/com.offlineinc.dumbdownlauncher/sources/za/i0.java) computes a
`subscriptionLocked` flag for every tile, grouping packages into three bundles:

- **essentials** (`f11539a`): `com.whatsapp`, `com.ubercab.uberlite`,
  `org.thoughtcrime.securesms`, `com.azure.authenticator`, plus built-ins `__CONTACT_SYNC__`,
  `__DUMB_MAP__`, `__QUACK__`, `__SNAKE__`, `__WEATHER__`.
- **audio** (`f11540b`): `com.spotify.music`, `com.apple.android.music`,
  `de.danoeh.antennapod`, `__DUMB_PODCAST__`.
- **smart text** (`f11541c`): `com.openbubbles.messaging`, `__GOOGLE_MESSAGES__`.

A tile is locked unless one of these holds:

```32:34:decomp/com.offlineinc.dumbdownlauncher/sources/za/i0.java
            if (sharedPreferences.getBoolean("has_subscription", true) || (!f11539a.contains(packageName) && ((sharedPreferences.getBoolean("hide_audio_bundle", false) || !f11540b.contains(packageName)) && (sharedPreferences.getBoolean("hide_smart_txt", false) || !f11541c.contains(packageName))))) {
                z3 = false;
            }
```

In plain terms, a tile stays **unlocked** when `has_subscription == true` (unlocks
everything), or the package is in no bundle, or it is in the audio/smart-text bundle but the
matching `hide_*` flag is `true`. The `hide_*` flags are inverted: when a bundle is *removed*
from a plan the app sets its `hide_*` flag `true`, so those tiles fall through to "not gated"
rather than "locked." The clean unlock is therefore `has_subscription=true` with both
`hide_*` flags `false`.

### Where the flags come from

[`decomp/.../cc/b.java`](decomp/com.offlineinc.dumbdownlauncher/sources/cc/b.java)
(`DeviceRegistrar`) resolves the SIM's phone number, registers the device, then calls
`refreshBundleFlags`, which reads the plan from the backend
([`decomp/.../zb/b.java`](decomp/com.offlineinc.dumbdownlauncher/sources/zb/b.java)) and writes
the three flags. Two failure modes matter:

- **404 (phone not found):** it defaults to the *unlocked* values
  (`hasSubscription=true`, both `hide_*` false).
- **Any other error (network/DNS failure):** it returns early and leaves the existing flags
  untouched — the "skipped" branch.

```243:246:decomp/com.offlineinc.dumbdownlauncher/sources/cc/b.java
        } catch (Exception e10) {
            Log.w("DeviceRegistrar", "refreshBundleFlags: skipped (" + e10.getMessage() + ")");
            return;
        }
```

That "skipped" branch is what makes a fix durable: if the backend host can't be reached at
all, the refresh throws and any values we wrote survive.

## Magisk module: `magisk/`

Rather than patch the APK, the [module](magisk/service.sh) writes the unlocked flag values
directly into `device_pairing.xml` at boot and null-routes the backend so a refresh can never
overwrite them.

```mermaid
flowchart TD
  boot["Boot (Magisk late_start)"] --> svc["service.sh rewrites device_pairing.xml"]
  svc --> flags["has_subscription=true, hide_smart_txt=false, hide_audio_bundle=false"]
  svc --> stop["am force-stop launcher"]
  stop --> reload["Launcher reloads prefs, rebuilds tiles"]
  reload --> gate["za.i0.a: subscriptionLocked=false for every tile"]
  gate --> tiles["All tiles open, no plan modal"]
  backend["offline-dc Heroku backend"] -->|null-routed via system/etc/hosts| refresh["refreshBundleFlags"]
  refresh -.->|"throws, hits skipped branch"| flags
```

Why each piece is needed:

- **Writing the flags** unlocks the tiles immediately, without waiting on any server.
- **Force-stopping the launcher** clears its in-memory tile caches so the new flags take
  effect on next launch.
- **Null-routing the host** ([systemless hosts](magisk/system/etc/hosts)) forces
  `refreshBundleFlags` into its "skipped" branch instead of ever fetching real plan data.

### Install

```bash
cd magisk && zip -r ../dumbdown-plan-unlock.zip . && cd ..
adb push dumbdown-plan-unlock.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/dumbdown-plan-unlock.zip'
adb reboot
```

If Magisk only extracts `module.prop` (it has on this device), copy `service.sh` and
`system/etc/hosts` into the installed module directory manually and `chmod 0755 service.sh`.

### Revert

Remove the module in the Magisk app (or delete `/data/adb/modules/dumbdown_plan_unlock`) and
reboot. The launcher's own flags stay as last written; delete `device_pairing.xml` if you
want a clean slate.

## Cloning

The APKs are stored with [Git LFS](https://git-lfs.com). Install it before cloning, or you
will get small text pointer files instead of real APKs:

```bash
brew install git-lfs && git lfs install
git clone https://github.com/ambercaravalho/tcl-flip-2-apps.git
```

Already cloned without it? Run `git lfs pull` to fetch the real files. All three offlineinc
packages are single-APK, so a normal install restores any of them:

```bash
adb install apks/com.offlineinc.dumbdownlauncher/base.apk
```
