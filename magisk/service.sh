#!/system/bin/sh
# DumbDown Plan Unlock - runs in Magisk late_start service mode (after user unlock,
# so credential-encrypted /data/data is readable).
#
# It forces the launcher's own entitlement flags to their "unlocked" values. The
# gate in za.i0 unlocks every tile when has_subscription==true; the two hide_*
# flags are pinned false so bundles that are removed (not just locked) reappear.
# The backend that would re-lock these is null-routed via system/etc/hosts, so a
# refresh throws and hits the app's "skipped" branch, leaving our values intact.

MODDIR=${0%/*}
PKG=com.offlineinc.dumbdownlauncher
PREFS=/data/data/$PKG/shared_prefs/device_pairing.xml
LOG=/data/local/tmp/dumbdown_plan_unlock.log

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

# 1. Wait for CE storage / prefs file to become readable (FBE unlock timing).
i=0
while [ ! -r "$PREFS" ] && [ "$i" -lt 180 ]; do
    sleep 2
    i=$((i + 2))
done
if [ ! -r "$PREFS" ]; then
    log "prefs not accessible after ${i}s - skipping this boot"
    exit 0
fi

tmp="$(dirname "$PREFS")/.device_pairing.unlock.tmp"

# 2. Rewrite the three entitlement booleans to unlocked values. awk both replaces
#    existing keys and inserts any that are missing (before </map>), so it is
#    idempotent and safe regardless of the file's current contents.
awk '
    /<boolean name="has_subscription"/  { print "    <boolean name=\"has_subscription\" value=\"true\" />"; seen_sub=1; next }
    /<boolean name="hide_smart_txt"/    { print "    <boolean name=\"hide_smart_txt\" value=\"false\" />"; seen_smart=1; next }
    /<boolean name="hide_audio_bundle"/ { print "    <boolean name=\"hide_audio_bundle\" value=\"false\" />"; seen_audio=1; next }
    /<\/map>/ {
        if (!seen_sub)   print "    <boolean name=\"has_subscription\" value=\"true\" />"
        if (!seen_smart) print "    <boolean name=\"hide_smart_txt\" value=\"false\" />"
        if (!seen_audio) print "    <boolean name=\"hide_audio_bundle\" value=\"false\" />"
        print; next
    }
    { print }
' "$PREFS" > "$tmp" 2>/dev/null

if [ ! -s "$tmp" ]; then
    log "awk produced empty output - aborting without touching prefs"
    rm -f "$tmp"
    exit 0
fi

# 3. Only apply (and restart the app) if something actually changed.
if cmp -s "$tmp" "$PREFS"; then
    log "prefs already unlocked - no change"
    rm -f "$tmp"
    exit 0
fi

# Overwrite in place via redirection so the file keeps its owner, mode and
# SELinux context (writing into the existing inode rather than renaming).
cat "$tmp" > "$PREFS"
rm -f "$tmp"
restorecon "$PREFS" 2>/dev/null

# 4. Force-stop so the launcher reloads prefs and rebuilds its tile list (this
#    also clears the in-memory caches AllAppsActivity.f2158w0 / MainAppsGridActivity.f2180z0).
am force-stop "$PKG" 2>/dev/null
log "patched prefs and force-stopped $PKG"
exit 0
