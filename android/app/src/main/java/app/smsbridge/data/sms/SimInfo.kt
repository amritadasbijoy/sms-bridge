package app.smsbridge.data.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** "SIM 1 · Grameenphone": which SIM slot a message used, for the browser's labels. */
data class Sim(val slot: Int, val carrier: String?)

/**
 * Maps Android's subscription ids to SIM slots (1-based) and carrier names. Needs the optional
 * "Phone" permission; without it messages simply carry no SIM label.
 */
@Singleton
class SimInfo @Inject constructor(@ApplicationContext private val context: Context) {

    fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun of(subscriptionId: Int?): Sim? {
        if (subscriptionId == null || subscriptionId < 0 || !canRead()) return null
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
            val sims = manager.activeSubscriptionInfoList.orEmpty()
            // Only worth a label when the phone has two SIMs.
            if (sims.size < 2) return null
            val info = sims.firstOrNull { it.subscriptionId == subscriptionId } ?: return null
            Sim(info.simSlotIndex + 1, info.carrierName?.toString()?.trim()?.ifEmpty { null } ?: info.displayName?.toString()?.trim()?.ifEmpty { null })
        } catch (e: SecurityException) {
            null
        }
    }
}
