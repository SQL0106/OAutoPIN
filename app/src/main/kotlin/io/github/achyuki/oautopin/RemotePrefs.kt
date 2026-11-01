package io.github.achyuki.oautopin

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Configuration store shared between the settings UI and the hooked
 * `com.android.systemui` process.
 *
 * The store itself is owned by the libxposed framework: SystemUI reads it through
 * [XposedModule.getRemotePreferences] and the UI writes it by binding to the
 * framework service (see [PinStore]). No world-readable file is ever created, so
 * only a process the framework injects into can read the PIN.
 *
 * In particular the PIN is stored in the user's **credential-encrypted** area: it
 * is unreadable while the device sits in BFU, which is exactly what keeps a
 * bootloader-unlocked attacker from booting a custom image and copying the PIN out.
 */
internal object RemotePrefs {
    /** RemotePreferences group. */
    const val GROUP = "oautopin"

    /** PIN of card slot 1 (the historical single-PIN key, kept for upgrades). */
    const val KEY_PIN_SLOT1 = "pin"

    /** PIN of card slot 2. Absent means "same as slot 1". */
    const val KEY_PIN_SLOT2 = "pin_slot2"

    /** Whether auto-unlock is allowed before the first device unlock (BFU). */
    const val KEY_ALLOW_BFU = "allow_bfu"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun open(module: XposedModule): SharedPreferences? {
        cached?.let { return it }
        return try {
            if (module.frameworkProperties and XposedInterface.PROP_CAP_REMOTE == 0L) {
                moduleLog(module, Log.WARN, "framework does not advertise remote preferences")
                return null
            }
            module.getRemotePreferences(GROUP).also { cached = it }
        } catch (t: Throwable) {
            moduleLog(
                module,
                Log.ERROR,
                "getRemotePreferences($GROUP) failed: ${t.javaClass.name}: ${t.message}"
            )
            null
        }
    }

    /** Forgets the cached handle so the next read picks up a fresh snapshot. */
    fun invalidate() {
        cached = null
    }

    /** Reads the whole configuration; never throws. */
    fun read(module: XposedModule): PinConfig {
        val preferences = open(module) ?: return PinConfig()
        return try {
            PinConfig(
                slot1 = PinConfig.sanitize(preferences.getString(KEY_PIN_SLOT1, null)),
                slot2 = PinConfig.sanitize(preferences.getString(KEY_PIN_SLOT2, null)),
                allowBfu = preferences.getBoolean(KEY_ALLOW_BFU, false),
            )
        } catch (t: Throwable) {
            moduleLog(
                module,
                Log.ERROR,
                "reading RemotePreferences failed: ${t.javaClass.name}: ${t.message}"
            )
            PinConfig()
        }
    }
}

/**
 * Writes a diagnostic line to logcat and, when running inside a hooked process, to
 * the framework log as well. Diagnostics must never break the host process, so
 * every sink is guarded.
 */
internal fun moduleLog(module: XposedModule?, priority: Int, message: String) {
    try {
        Log.println(priority, "OAutoPIN", message)
    } catch (_: Throwable) {
        // logcat is best effort only.
    }
    if (module != null) {
        try {
            module.log(priority, "OAutoPIN", message)
        } catch (_: Throwable) {
            // The framework sink is best effort only.
        }
    }
}
