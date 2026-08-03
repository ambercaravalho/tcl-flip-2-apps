# tcl-flip-2-apps

APK backup of the user-installed apps on my TCL Flip 2 (model `4058G`, Android 11 / SDK 30, rooted with Magisk).

This is an **APK-only** backup. It does not contain app data, so no messages, logins, or settings are
included here. Restoring an app gives you a fresh install that you will need to sign into again.

## Contents

Captured with `./backup-apks.sh`. One directory per package, plus `apks/manifest.tsv` recording the
version of each app at the time of backup.

| Package | App | Version | APKs |
| --- | --- | --- | --- |
| `com.apple.android.music` | Apple Music | 4.6.0 | 1 |
| `com.flipos.launcher` | FlipOS Launcher | 1.0 | 1 |
| `com.offlineinc.dumbdownlauncher` | DumbDown Launcher | v5.51.0 | 1 |
| `com.offlineinc.dumbtt9` | DumbTT9 | 1.0 | 1 |
| `com.offlineinc.voicetotext` | Voice to Text | 1.0 | 1 |
| `com.openbubbles.messaging` | OpenBubbles | 1.15.0 | 77 |
| `com.spotify.music` | Spotify | 8.7.32.1554 | 1 |
| `com.topjohnwu.magisk` | Magisk | 30.7 | 1 |
| `com.ubercab.uberlite` | Uber Lite | 1.146.10004 | 1 |
| `com.whatsapp` | WhatsApp | 2.26.27.70 | 1 |

Total: 86 APK files, ~756 MB.

## Cloning

The APKs are stored with [Git LFS](https://git-lfs.com), because three of them exceed GitHub's 100 MB
per-file limit. Install it before cloning, or you will get small text pointer files instead of real
APKs:

```bash
brew install git-lfs && git lfs install
git clone https://github.com/ambercaravalho/tcl-flip-2-apps.git
```

Already cloned without it? Run `git lfs pull` to fetch the real files.

## Making a new backup

```bash
./backup-apks.sh              # writes to ./apks
./backup-apks.sh /some/where  # or a directory of your choosing
```

Requires `adb` on your `PATH` and exactly one device attached. Root is used only as a fallback for
APKs the shell user cannot read directly.

## Restoring

For the nine single-APK packages, a normal install works:

```bash
adb install apks/com.whatsapp/base.apk
```

**OpenBubbles is a split APK** — it ships as a base plus 76 architecture, density, and language
splits. Installing `base.apk` on its own will fail or crash on launch. Install the whole set at once:

```bash
adb install-multiple apks/com.openbubbles.messaging/*.apk
```

The 77 files are `base.apk`, one ABI split (`split_config.armeabi_v7a.apk`), one density split
(`split_config.xhdpi.apk`), and 74 language splits. If you want a minimal install rather than all 74
languages, four files are enough:

```bash
cd apks/com.openbubbles.messaging
adb install-multiple base.apk split_config.armeabi_v7a.apk split_config.xhdpi.apk split_config.en.apk
```

### Notes on specific apps

- **Magisk** — reinstalling the APK only restores the manager app. It does not re-root the phone, and
  it will not restore your module list or superuser grants.
- **FlipOS Launcher** — preinstalled by TCL. It lives in `/data/app` rather than the system partition
  on this device, which is why it appears here as a user app.
