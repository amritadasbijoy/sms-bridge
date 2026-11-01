package app.smsbridge

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.SmsBridgeMigrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrates every exported schema version with the real migration list and checks that rows
 * survive with correct values. Add a case with seeded rows for each new migration.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmsBridgeDatabase::class.java,
    )

    private fun insertV1(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String, address: String?) {
        db.execSQL(
            "INSERT INTO sms_messages (id, provider_id, thread_id, address, body, date, date_sent, type, read, seen, " +
                "subscription_id, status, error_code, source_device_id, fingerprint, content_key, origin, created_at, updated_at) " +
                "VALUES (?, 1, 1, ?, 'Synthetic', 1000, 900, 'INBOX', 1, 1, NULL, NULL, NULL, 'd1', 'fp', 'ck', 'PROVIDER_SCAN', 1, 1)",
            arrayOf(id, address),
        )
    }

    @Test
    fun migrate1To2BackfillsAddressKey() {
        helper.createDatabase(dbName, 1).use { db ->
            insertV1(db, "m1", "+880 1711-000000")
            insertV1(db, "m2", "01711000000")
            insertV1(db, "m3", "bKash")
            insertV1(db, "m4", null)
        }

        helper.runMigrationsAndValidate(dbName, 2, true, SmsBridgeMigrations.MIGRATION_1_2).use { db ->
            val keys = mutableMapOf<String, String>()
            db.query("SELECT id, address_key FROM sms_messages").use { c ->
                while (c.moveToNext()) keys[c.getString(0)] = c.getString(1)
            }
            assertEquals(
                mapOf("m1" to "1711000000", "m2" to "1711000000", "m3" to "bkash", "m4" to ""),
                keys,
            )
        }
    }

    @Test
    fun allVersionsOpenWithRoomWithoutDataLoss() {
        helper.createDatabase(dbName, 1).use { db -> insertV1(db, "m1", "+15550100") }

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SmsBridgeDatabase::class.java,
            dbName,
        ).addMigrations(*SmsBridgeMigrations.ALL).build()
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sms_messages").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }
}
