#!/system/bin/sh
# lsposed_notif_off - disable LSPosed's persistent "LSPosed loaded" status
# notification by flipping LSPosed's own preference.
#
# LSPosed stores framework prefs in the SQLite database
#   /data/adb/lspd/config/modules_config.db
# and reads `enable_status_notification` at BOOT_COMPLETED
# (LSPosedService.dispatchBootCompleted): when false it cancels the notification
# instead of posting it. The manager's "Status Notification" toggle writes this
# same value, but on this phone that toggle is greyed out (parasitic manager +
# the flip launcher can't pin the shortcut it wants first).
#
# So we write the value ourselves. `lspd_status_notif` is a small static ARM
# binary (built from the official SQLite amalgamation + src/lspd_status_notif.c)
# that sets the row to a serialized Boolean.FALSE. We run in post-fs-data, before
# system_server starts, so the DB is not open by anyone and the value is in place
# well before BOOT_COMPLETED. Idempotent: safe to run every boot, and re-applies
# the setting if LSPosed is ever reinstalled/reset.

MODDIR=${0%/*}
DB=/data/adb/lspd/config/modules_config.db
BIN="$MODDIR/lspd_status_notif"
LOG=/data/local/tmp/lsposed_notif_off.log

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

if [ ! -f "$DB" ]; then
    log "no $DB yet (LSPosed not installed?) - skipping this boot"
    exit 0
fi
if [ ! -f "$BIN" ]; then
    log "missing $BIN - skipping"
    exit 0
fi

chmod 0755 "$BIN" 2>/dev/null

out=$("$BIN" 0 "$DB" 2>&1)
rc=$?

# Keep root ownership / SELinux context sane for files sqlite may have created.
chown root:root "$DB" "$DB-wal" "$DB-shm" 2>/dev/null
restorecon "$DB" "$DB-wal" "$DB-shm" 2>/dev/null

if [ "$rc" -eq 0 ]; then
    log "ok: $out"
else
    log "FAILED (rc=$rc): $out"
fi
exit 0
