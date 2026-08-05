#!/system/bin/sh
# SECURITY-REVIEW: runs as root and toggles Magisk's Zygisk setting, which changes
# what code is injected into every Zygote-spawned process. Reversible by setting
# the value back to 0 and rebooting.
magisk --sqlite "REPLACE INTO settings (key,value) VALUES('zygisk',1)"
echo "--- settings after ---"
magisk --sqlite "select * from settings"
