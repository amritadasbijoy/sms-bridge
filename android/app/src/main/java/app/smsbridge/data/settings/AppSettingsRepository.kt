package app.smsbridge.data.settings

import android.os.Build
import androidx.room.withTransaction
import app.smsbridge.core.common.TimeSource
import app.smsbridge.core.database.SmsBridgeDatabase
import app.smsbridge.core.database.entity.AppSettingEntity
import app.smsbridge.core.database.entity.DeviceEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small key/value settings persisted in Room (so they share the database's migration path). */
@Singleton
class AppSettingsRepository @Inject constructor(private val db: SmsBridgeDatabase) {
    private val dao get() = db.appSettingDao()

    val syncPaused: Flow<Boolean> = dao.observe(KEY_SYNC_PAUSED).map { it == true.toString() }

    suspend fun isSyncPaused(): Boolean = dao.get(KEY_SYNC_PAUSED) == true.toString()

    suspend fun setSyncPaused(paused: Boolean) = dao.put(AppSettingEntity(KEY_SYNC_PAUSED, paused.toString()))

    /** Off by default: some carriers charge for, or mishandle, status reports. */
    val deliveryReportsEnabled: Flow<Boolean> = dao.observe(KEY_DELIVERY_REPORTS).map { it == true.toString() }

    suspend fun isDeliveryReportsEnabled(): Boolean = dao.get(KEY_DELIVERY_REPORTS) == true.toString()

    suspend fun setDeliveryReportsEnabled(enabled: Boolean) =
        dao.put(AppSettingEntity(KEY_DELIVERY_REPORTS, enabled.toString()))

    /** True once the first full provider import has finished. */
    suspend fun isInitialImportCompleted(): Boolean = dao.get(KEY_INITIAL_IMPORT_DONE) == true.toString()

    suspend fun setInitialImportCompleted() = dao.put(AppSettingEntity(KEY_INITIAL_IMPORT_DONE, true.toString()))

    /** Monotonic per-device event sequence. Never reused, even after events are pruned. */
    suspend fun nextDeviceSequence(): Long = db.withTransaction {
        val next = (dao.get(KEY_DEVICE_SEQUENCE)?.toLongOrNull() ?: 0L) + 1
        dao.put(AppSettingEntity(KEY_DEVICE_SEQUENCE, next.toString()))
        next
    }

    suspend fun getString(key: String): String? = dao.get(key)

    suspend fun putString(key: String, value: String) = dao.put(AppSettingEntity(key, value))

    fun observeString(key: String): Flow<String?> = dao.observe(key)

    suspend fun deletePrefix(prefix: String) = dao.deletePrefix(prefix)

    private companion object {
        const val KEY_SYNC_PAUSED = "sync_paused"
        const val KEY_INITIAL_IMPORT_DONE = "initial_import_done"
        const val KEY_DEVICE_SEQUENCE = "device_sequence"
        const val KEY_DELIVERY_REPORTS = "delivery_reports"
    }
}

/** Identity of this installation as a SMS Bridge device. Generated once, never derived from hardware ids. */
@Singleton
class LocalDeviceRepository @Inject constructor(
    private val db: SmsBridgeDatabase,
    private val time: TimeSource,
) {
    private val mutex = Mutex()

    @Volatile
    private var cachedId: String? = null

    val localDevice: Flow<DeviceEntity?> = db.deviceDao().observeLocal()

    suspend fun localDeviceId(): String = cachedId ?: mutex.withLock {
        cachedId ?: db.withTransaction {
            db.deviceDao().getLocal()?.id ?: UUID.randomUUID().toString().also { id ->
                db.deviceDao().upsert(
                    DeviceEntity(
                        id = id,
                        name = defaultDeviceName(),
                        isLocal = true,
                        createdAt = time.nowMillis(),
                        lastSeenAt = null,
                        revokedAt = null,
                    ),
                )
            }
        }.also { cachedId = it }
    }

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return when {
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model".trim()
        }.ifEmpty { "Android device" }
    }
}
