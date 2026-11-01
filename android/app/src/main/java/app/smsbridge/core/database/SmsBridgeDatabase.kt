package app.smsbridge.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.smsbridge.core.model.AddressKey
import app.smsbridge.core.database.dao.AppSettingDao
import app.smsbridge.core.database.dao.BackupHistoryDao
import app.smsbridge.core.database.dao.DeviceDao
import app.smsbridge.core.database.dao.SmsMessageDao
import app.smsbridge.core.database.dao.SyncCheckpointDao
import app.smsbridge.core.database.dao.SyncOperationDao
import app.smsbridge.core.database.entity.AppSettingEntity
import app.smsbridge.core.database.entity.BackupHistoryEntity
import app.smsbridge.core.database.entity.DeviceEntity
import app.smsbridge.core.database.entity.SmsMessageEntity
import app.smsbridge.core.database.entity.SyncCheckpointEntity
import app.smsbridge.core.database.entity.SyncOperationEntity
import app.smsbridge.core.database.entity.TransferSessionEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        SmsMessageEntity::class,
        DeviceEntity::class,
        SyncOperationEntity::class,
        SyncCheckpointEntity::class,
        BackupHistoryEntity::class,
        TransferSessionEntity::class,
        AppSettingEntity::class,
    ],
    version = SmsBridgeDatabase.VERSION,
    exportSchema = true,
)
abstract class SmsBridgeDatabase : RoomDatabase() {
    abstract fun smsMessageDao(): SmsMessageDao
    abstract fun syncOperationDao(): SyncOperationDao
    abstract fun syncCheckpointDao(): SyncCheckpointDao
    abstract fun deviceDao(): DeviceDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun backupHistoryDao(): BackupHistoryDao

    companion object {
        const val NAME = "sms_bridge.db"
        const val VERSION = 2
    }
}

/**
 * Every schema change ships an explicit [Migration] here plus a test in
 * `androidTest/.../DatabaseMigrationTest`. Destructive fallback is deliberately never enabled:
 * a failed migration must crash loudly rather than wipe the user's archive.
 */
object SmsBridgeMigrations {
    /** v2: conversation grouping key. Backfilled in Kotlin so it matches the app's normalization exactly. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `sms_messages` ADD COLUMN `address_key` TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sms_messages_address_key_date` ON `sms_messages` (`address_key`, `date`)",
            )
            val keys = HashMap<String, String>()
            db.query("SELECT DISTINCT `address` FROM `sms_messages` WHERE `address` IS NOT NULL").use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(0)
                    keys[address] = AddressKey.of(address)
                }
            }
            keys.forEach { (address, key) ->
                if (key.isNotEmpty()) {
                    db.execSQL("UPDATE `sms_messages` SET `address_key` = ? WHERE `address` = ?", arrayOf<Any>(key, address))
                }
            }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SmsBridgeDatabase =
        Room.databaseBuilder(context, SmsBridgeDatabase::class.java, SmsBridgeDatabase.NAME)
            .addMigrations(*SmsBridgeMigrations.ALL)
            .build()
}
