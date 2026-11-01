package app.smsbridge

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.SmsBridgeMigrations
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens every exported schema version and migrates it to the current one with the real
 * migration list. Add a `migrateNtoM` case with seeded rows for each new migration.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmsBridgeDatabase::class.java,
    )

    @Test
    fun allVersionsMigrateToLatestWithoutDataLoss() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO sms_messages (id, provider_id, thread_id, address, body, date, date_sent, type, read, seen, " +
                    "subscription_id, status, error_code, source_device_id, fingerprint, content_key, origin, created_at, updated_at) " +
                    "VALUES ('m1', 1, 1, '+15550100', 'Synthetic', 1000, 900, 'INBOX', 1, 1, NULL, NULL, NULL, 'd1', 'fp', 'ck', 'PROVIDER_SCAN', 1, 1)",
            )
        }

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SmsBridgeDatabase::class.java,
            dbName,
        ).addMigrations(*SmsBridgeMigrations.ALL).build()
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sms_messages").use { c ->
            c.moveToFirst()
            check(c.getInt(0) == 1) { "row lost during migration" }
        }
        db.close()
    }
}
