package io.github.achyuki.oautopin

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * App-side access to the framework-owned RemotePreferences that [Hook] reads
 * inside `com.android.systemui`.
 *
 * Writes go through the libxposed framework service, which owns the store; this
 * process only borrows it. No world-readable preference file is created, so the
 * PIN stays inside credential-encrypted storage and is unreadable in BFU.
 *
 * Every call here can block for up to [SERVICE_TIMEOUT_MS] while the framework
 * service binds, so the UI must call it off the main thread.
 */
internal object PinStore {

    private const val TAG = "OAutoPIN"

    /** How long a caller waits for the framework service to bind. */
    private const val SERVICE_TIMEOUT_MS = 3_000L

    private val lock = Any()

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var listenerRegistered = false

    /** Set when the framework service turns out to be unavailable. */
    @Volatile
    var lastError: String? = null
        private set

    // ------------------------------------------------------------------ service binding

    /**
     * Registers the framework service listener once per process.
     *
     * The binding is initiated by the framework when this module's own process
     * starts, so this only has to register early.
     */
    fun bind() {
        if (listenerRegistered) return
        synchronized(lock) {
            if (listenerRegistered) return
            try {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(bound: XposedService) {
                        synchronized(lock) {
                            service = bound
                            lastError = null
                            (lock as Object).notifyAll()
                        }
                        Log.i(
                            TAG,
                            "framework service bound: api=${bound.apiVersion} " +
                                "framework=${bound.frameworkName}/${bound.frameworkVersion}"
                        )
                    }

                    override fun onServiceDied(dead: XposedService) {
                        synchronized(lock) {
                            if (service === dead) service = null
                            (lock as Object).notifyAll()
                        }
                        Log.w(TAG, "framework service died")
                    }
                })
                listenerRegistered = true
            } catch (t: Throwable) {
                recordError("cannot register framework service listener", t)
            }
        }
    }

    private fun awaitService(): XposedService? {
        synchronized(lock) {
            val deadline = SystemClock.elapsedRealtime() + SERVICE_TIMEOUT_MS
            var current = service
            while (current == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    (lock as Object).wait(remaining)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                current = service
            }
            if (current == null) {
                lastError = "framework service unavailable; open this module from LSPosed " +
                    "and make sure the module is enabled"
                return null
            }
            return current
        }
    }

    private fun remotePreferences(): SharedPreferences? {
        val current = awaitService() ?: return null
        return try {
            if (current.apiVersion < XposedService.API_101 ||
                (current.frameworkProperties and XposedService.PROP_CAP_REMOTE) == 0L
            ) {
                lastError = "framework does not support remote preferences " +
                    "(api=${current.apiVersion})"
                return null
            }
            current.getRemotePreferences(RemotePrefs.GROUP)
        } catch (t: Throwable) {
            recordError("getRemotePreferences(${RemotePrefs.GROUP}) failed", t)
            null
        }
    }

    // ------------------------------------------------------------------ configuration access

    /** @return the stored configuration; all-empty when nothing has been saved. */
    fun readConfig(): PinConfig {
        val preferences = remotePreferences() ?: return PinConfig()
        return try {
            PinConfig(
                slot1 = PinConfig.sanitize(
                    preferences.getString(RemotePrefs.KEY_PIN_SLOT1, null)
                ),
                slot2 = PinConfig.sanitize(
                    preferences.getString(RemotePrefs.KEY_PIN_SLOT2, null)
                ),
                allowBfu = preferences.getBoolean(RemotePrefs.KEY_ALLOW_BFU, false),
            ).also { lastError = null }
        } catch (t: Throwable) {
            recordError("reading stored configuration failed", t)
            PinConfig()
        }
    }

    /**
     * Persists the configuration into framework-owned RemotePreferences.
     *
     * `commit()` is used instead of `apply()` so the UI can report the real
     * outcome and the value is durable before SystemUI restarts.
     *
     * @return true when the framework accepted and can read back every value.
     */
    fun writeConfig(config: PinConfig): Boolean {
        val preferences = remotePreferences() ?: return false
        return try {
            val editor = preferences.edit()
            putOrRemove(editor, RemotePrefs.KEY_PIN_SLOT1, config.slot1)
            putOrRemove(editor, RemotePrefs.KEY_PIN_SLOT2, config.slot2)
            editor.putBoolean(RemotePrefs.KEY_ALLOW_BFU, config.allowBfu)
            val committed = editor.commit()
            val readBack = PinConfig(
                slot1 = preferences.getString(RemotePrefs.KEY_PIN_SLOT1, null),
                slot2 = preferences.getString(RemotePrefs.KEY_PIN_SLOT2, null),
                allowBfu = preferences.getBoolean(RemotePrefs.KEY_ALLOW_BFU, false),
            )
            val accepted = committed && readBack == config
            lastError = if (accepted) null else "framework rejected the new settings"
            accepted
        } catch (t: Throwable) {
            recordError("saving configuration failed", t)
            false
        }
    }

    private fun putOrRemove(
        editor: SharedPreferences.Editor,
        key: String,
        value: String?,
    ) {
        if (value == null) editor.remove(key) else editor.putString(key, value)
    }

    private fun recordError(message: String, t: Throwable) {
        val detail = "$message: ${t.javaClass.name}: ${t.message}"
        lastError = detail
        Log.e(TAG, detail, t)
    }
}
