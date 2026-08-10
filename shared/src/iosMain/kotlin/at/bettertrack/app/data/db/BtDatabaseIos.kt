package at.bettertrack.app.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

// ─────────────────────────────────────────────────────────────────────────────
// KMP/iOS port (Phase 2): the iOS construction of [BtDatabase].
//
// iOS has no existing installs, so there is no historical data to migrate: the
// database is opened FRESH at v10 and Room creates the full v10 schema on first
// open (BtDatabase_Impl.createAllTables — the exact tables the golden v10 schema
// describes). No `addMigrations(...)` is registered, on purpose — the nine
// Android migrations exist only to walk an already-installed device forward, a
// situation that cannot occur here. If a future iOS release bumps the schema,
// iOS migrations get added HERE (rewritten to the SQLiteConnection API), wholly
// independent of the Android chain in BtDatabaseAndroid.kt.
//
// Construction uses the KMP driver path (BundledSQLiteDriver over the bundled
// SQLite that ships with the app), NOT the Android SupportSQLite path — so there
// is no `openHelper` on iOS, which is fine: AccountDataManager (the only
// openHelper user) stays in :app and never compiles for iOS.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The iOS factory: a fresh v10 BetterTrack database under the app's Documents
 * directory, opened through the bundled SQLite driver.
 */
fun BtDatabase.Companion.create(): BtDatabase {
    val dbFilePath = documentDirectory() + "/bettertrack.db"
    return Room.databaseBuilder<BtDatabase>(name = dbFilePath)
        .setDriver(BundledSQLiteDriver())
        .build()
}

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path) {
        "NSDocumentDirectory has no path — cannot locate the BetterTrack database file"
    }
}
