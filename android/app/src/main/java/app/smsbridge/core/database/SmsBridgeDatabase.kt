package app.smsbridge.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import app.smsbridge.core.database.dao.AppSettingDao
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

    companion object {
        const val NAME = "sms_bridge.db"
        const val VERSION = 1
    }
}

/**
 * Every schema change ships an explicit [Migration] here plus a test in
 * `androidTest/.../DatabaseMigrationTest`. Destructive fallback is deliberately never enabled:
 * a failed migration must crash loudly rather than wipe the user's archive.
 */
object SmsBridgeMigrations {
    val ALL: Array<Migration> = emptyArray()
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
