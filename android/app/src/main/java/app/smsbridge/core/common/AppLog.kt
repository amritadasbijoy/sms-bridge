package app.smsbridge.core.common

import android.util.Log
import app.smsbridge.BuildConfig

/**
 * The only logging entry point in the app.
 *
 * Rule: callers pass counts, ids, states and exception class names only. Message bodies,
 * phone numbers, OTPs and key material must never be passed here. Exceptions are reduced
 * to their class name because SQL and provider errors can embed row values in the message.
 */
object AppLog {
    private const val PREFIX = "SmsBridge/"

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(PREFIX + tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(PREFIX + tag, describe(message, error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(PREFIX + tag, describe(message, error))
    }

    private fun describe(message: String, error: Throwable?): String =
        if (error == null) message else "$message (${error.javaClass.simpleName})"
}
