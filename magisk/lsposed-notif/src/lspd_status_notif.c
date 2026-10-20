/*
 * lspd_status_notif - toggle LSPosed's "Status Notification" preference directly
 * in its config database, the same value the (here greyed-out) manager toggle
 * writes.
 *
 * LSPosed stores framework prefs in the SQLite DB
 *   /data/adb/lspd/config/modules_config.db
 * table `configs (module_pkg_name, user_id, `group`, `key`, data BLOB)`. The key
 * `enable_status_notification` (module_pkg_name='lspd', user_id=0, group='config')
 * defaults to true when the row is absent. The daemon reads it at BOOT_COMPLETED
 * (LSPosedService.dispatchBootCompleted) and, when false, cancels the persistent
 * "LSPosed loaded" notification instead of posting it.
 *
 * `data` is an Apache-Commons SerializationUtils / java.io.ObjectOutputStream
 * blob, i.e. a serialized java.lang.Boolean. The two constant encodings below are
 * exactly what `new ObjectOutputStream(...).writeObject(Boolean.FALSE/TRUE)`
 * produces (verified against the JDK).
 *
 * Usage: lspd_status_notif [0|1] [db_path]
 *   0 (default) -> disable the status notification
 *   1           -> re-enable it
 * Exit code 0 on success, non-zero on error.
 */
#include "sqlite3.h"
#include <stdio.h>
#include <string.h>

/* ACED0005 7372 0011 "java.lang.Boolean" CD207280D59CFAEE 02 0001 5A 0005 "value" 78 70 <00|01> */
static const unsigned char BOOL_FALSE[] = {
    0xAC,0xED,0x00,0x05,0x73,0x72,0x00,0x11,
    'j','a','v','a','.','l','a','n','g','.','B','o','o','l','e','a','n',
    0xCD,0x20,0x72,0x80,0xD5,0x9C,0xFA,0xEE,0x02,0x00,0x01,
    0x5A,0x00,0x05,'v','a','l','u','e',0x78,0x70,0x00
};
static const unsigned char BOOL_TRUE[] = {
    0xAC,0xED,0x00,0x05,0x73,0x72,0x00,0x11,
    'j','a','v','a','.','l','a','n','g','.','B','o','o','l','e','a','n',
    0xCD,0x20,0x72,0x80,0xD5,0x9C,0xFA,0xEE,0x02,0x00,0x01,
    0x5A,0x00,0x05,'v','a','l','u','e',0x78,0x70,0x01
};

int main(int argc, char **argv) {
    const char *db_path = "/data/adb/lspd/config/modules_config.db";
    int enable = 0; /* default: disable the notification */

    if (argc > 1 && strcmp(argv[1], "1") == 0) enable = 1;
    if (argc > 2) db_path = argv[2];

    const unsigned char *blob = enable ? BOOL_TRUE : BOOL_FALSE;
    int blob_len = enable ? (int)sizeof(BOOL_TRUE) : (int)sizeof(BOOL_FALSE);

    sqlite3 *db = NULL;
    if (sqlite3_open(db_path, &db) != SQLITE_OK) {
        fprintf(stderr, "open %s failed: %s\n", db_path, sqlite3_errmsg(db));
        sqlite3_close(db);
        return 1;
    }
    sqlite3_busy_timeout(db, 3000);

    const char *sql =
        "INSERT OR REPLACE INTO configs "
        "(module_pkg_name,user_id,\"group\",\"key\",data) "
        "VALUES ('lspd',0,'config','enable_status_notification',?1);";
    sqlite3_stmt *st = NULL;
    if (sqlite3_prepare_v2(db, sql, -1, &st, NULL) != SQLITE_OK) {
        fprintf(stderr, "prepare failed: %s\n", sqlite3_errmsg(db));
        sqlite3_close(db);
        return 1;
    }
    sqlite3_bind_blob(st, 1, blob, blob_len, SQLITE_STATIC);
    if (sqlite3_step(st) != SQLITE_DONE) {
        fprintf(stderr, "write failed: %s\n", sqlite3_errmsg(db));
        sqlite3_finalize(st);
        sqlite3_close(db);
        return 1;
    }
    sqlite3_finalize(st);

    /* Fold the WAL back into the main db so the state is self-contained. */
    sqlite3_wal_checkpoint_v2(db, NULL, SQLITE_CHECKPOINT_TRUNCATE, NULL, NULL);
    sqlite3_close(db);

    printf("enable_status_notification=%s\n", enable ? "true" : "false");
    return 0;
}
