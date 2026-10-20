#!/system/bin/sh
# DumbDown unlock module - runs in Magisk late_start service mode (after user
# unlock, so credential-encrypted /data/data is readable).
#
# It repairs two features that depend on a backend that no longer exists
# (offline-dc-backend-...herokuapp.com, null-routed in system/etc/hosts) by
# writing the app's own "unlocked / activated" values into its SharedPreferences:
#
#   1. Plan/subscription gate (za.i0) - device_pairing:
#        has_subscription=true, hide_smart_txt=false, hide_audio_bundle=false
#      (hide_smart_txt=true instead while the companion DumbDown Hide Smart Txt
#      module, dumbdown_hide_smart_txt, is active - that module then defers to
#      this script so only one of them rewrites device_pairing.xml per boot)
#   2. Boot SIM-check / activation (MainActivity onboarding "boot_registration"
#      step) - device_pairing: is_paired=true, device_registered=true
#      and launcher_prefs: setup_skipped=true
#
# These are exactly the values the app itself writes on a successful pairing or
# when the user taps "skip pairing" / "skip setup" (za.p, za.o), so no app code
# is modified. With the backend null-routed, the background refresh throws and
# hits the app's "skipped" branch, leaving our values intact.

MODDIR=${0%/*}
PKG=com.offlineinc.dumbdownlauncher
PREFDIR=/data/data/$PKG/shared_prefs
PAIRING=$PREFDIR/device_pairing.xml
LAUNCHER=$PREFDIR/launcher_prefs.xml
LOG=/data/local/tmp/dumbdown_plan_unlock.log

CHANGED=0

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

# module_active <id> - installed, and not disabled or pending removal.
module_active() {
    d=/data/adb/modules/$1
    [ -d "$d" ] && [ ! -f "$d/disable" ] && [ ! -f "$d/remove" ]
}

# set_bool <prefs-file> <key> <value>
# Replaces an existing <boolean name="key" .../> or inserts one before </map>.
# Only patches a file that already exists (so we never create one with the wrong
# owner/SELinux context). Idempotent: writes back in place - preserving the
# inode, owner, mode and context - only when the content actually changes.
set_bool() {
    f=$1
    key=$2
    val=$3
    if [ ! -f "$f" ]; then
        log "skip $key: $(basename "$f") does not exist yet"
        return 0
    fi
    tmp="$f.unlock.tmp"
    awk -v k="$key" -v v="$val" '
        $0 ~ "<boolean name=\"" k "\"" {
            print "    <boolean name=\"" k "\" value=\"" v "\" />"; seen=1; next
        }
        /<\/map>/ {
            if (!seen) print "    <boolean name=\"" k "\" value=\"" v "\" />"
            print; next
        }
        { print }
    ' "$f" > "$tmp" 2>/dev/null
    if [ ! -s "$tmp" ]; then
        log "awk produced empty output for $key in $(basename "$f") - skipping"
        rm -f "$tmp"
        return 0
    fi
    if cmp -s "$tmp" "$f"; then
        rm -f "$tmp"
        return 0
    fi
    cat "$tmp" > "$f"
    rm -f "$tmp"
    restorecon "$f" 2>/dev/null
    log "set $key=$val in $(basename "$f")"
    CHANGED=1
}

# 1. Wait for CE storage / the primary prefs file to become readable (FBE unlock timing).
i=0
while [ ! -r "$PAIRING" ] && [ "$i" -lt 180 ]; do
    sleep 2
    i=$((i + 2))
done
if [ ! -r "$PAIRING" ]; then
    log "prefs not accessible after ${i}s - skipping this boot"
    exit 0
fi

# 2. Plan/subscription gate.
set_bool "$PAIRING" has_subscription true
if module_active dumbdown_hide_smart_txt; then
    SMART_TXT=true
else
    SMART_TXT=false
fi
set_bool "$PAIRING" hide_smart_txt "$SMART_TXT"
set_bool "$PAIRING" hide_audio_bundle false

# 3. Boot SIM-check / activation: mark the device paired + registered, and flag
#    setup as skipped so MainActivity never selects the "boot_registration" step.
set_bool "$PAIRING" is_paired true
set_bool "$PAIRING" device_registered true
set_bool "$LAUNCHER" setup_skipped true

# 4. If anything changed, force-stop so the launcher reloads prefs and rebuilds
#    its onboarding/tile state on next launch.
if [ "$CHANGED" -eq 1 ]; then
    am force-stop "$PKG" 2>/dev/null
    log "applied changes and force-stopped $PKG"
else
    log "prefs already in desired state - no change"
fi
exit 0
