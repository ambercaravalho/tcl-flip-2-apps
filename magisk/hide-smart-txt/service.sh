#!/system/bin/sh
# DumbDown hide smart txt module - runs in Magisk late_start service mode (after
# user unlock, so credential-encrypted /data/data is readable).
#
# Sets device_pairing: hide_smart_txt=true, the app's own switch for plans
# without smart txt. The launcher then drops the smart txt tile (OpenBubbles /
# Google Messages, per platform_choice) from the home grid and the contact sync
# tile from the app drawer, and skips the OpenBubbles linking flow.
#
# The DumbDown Plan Unlock module (dumbdown_plan_unlock) writes the same prefs
# file at boot, and Magisk runs every module's service.sh in parallel. To avoid
# two scripts rewriting device_pairing.xml at once, only one of them writes per
# boot: when Plan Unlock is active it sees this module and writes
# hide_smart_txt=true itself, and this script just exits.

MODDIR=${0%/*}
PKG=com.offlineinc.dumbdownlauncher
PREFDIR=/data/data/$PKG/shared_prefs
PAIRING=$PREFDIR/device_pairing.xml
LOG=/data/local/tmp/dumbdown_hide_smart_txt.log

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
    tmp="$f.hidesmarttxt.tmp"
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

if module_active dumbdown_plan_unlock; then
    log "dumbdown_plan_unlock is active - deferring to it to write hide_smart_txt=true"
    exit 0
fi

# Wait for CE storage / the prefs file to become readable (FBE unlock timing).
i=0
while [ ! -r "$PAIRING" ] && [ "$i" -lt 180 ]; do
    sleep 2
    i=$((i + 2))
done
if [ ! -r "$PAIRING" ]; then
    log "prefs not accessible after ${i}s - skipping this boot"
    exit 0
fi

set_bool "$PAIRING" hide_smart_txt true

if [ "$CHANGED" -eq 1 ]; then
    am force-stop "$PKG" 2>/dev/null
    log "applied changes and force-stopped $PKG"
else
    log "prefs already in desired state - no change"
fi
exit 0
