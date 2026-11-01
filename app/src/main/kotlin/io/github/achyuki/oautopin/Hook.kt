package io.github.achyuki.oautopin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.EditText
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Module entry point, declared in `META-INF/xposed/java_init.list` and driven by
 * the framework lifecycle. Only `com.android.systemui` is statically scoped.
 *
 * Behaviour, in order of precedence:
 *
 * 1. **AFU gate (default).** The SIM PIN is only submitted once the device has
 *    been unlocked by the user at least once (`UserManager.isUserUnlocked`). While
 *    the device is still in BFU the resume is recorded and nothing is submitted,
 *    so a phone that was merely rebooted — say, by somebody who found it — stays
 *    without cellular service until the owner unlocks it.
 * 2. **Opt-in BFU unlock.** If the user turns the switch on, the old behaviour is
 *    restored and the PIN is submitted even in BFU. That keeps cellular location
 *    reporting alive after an unattended reboot, at the cost of exposing the SIM
 *    (and its SMS channel) to whoever holds the powered device.
 * 3. **Dual SIM.** Each card slot gets its own PIN when the two differ; see
 *    [PinConfig.pinForSlot].
 *
 * The PIN itself comes from framework-owned RemotePreferences (see [RemotePrefs]),
 * i.e. from credential-encrypted storage: nothing is readable in BFU, so a
 * bootloader-unlocked attacker cannot boot a foreign image and copy the PIN out.
 */
class Hook : XposedModule() {

    private companion object {
        private const val TARGET_PACKAGE = "com.android.systemui"
        private const val HOOK_ID = "oautopin_sim_pin_onresume"

        private const val CONTROLLER_CLASS =
            "com.oplus.keyguard.security.view.OplusKeyguardSimInputViewController"

        private const val CONTROLLER_METHOD = "onResume"

        /** Field names that plausibly carry the card slot of a controller. */
        private val SLOT_FIELDS = listOf(
            "mSlotId", "mSlotIndex", "slotId", "slotIndex",
            "mPhoneId", "phoneId", "mPhoneIndex", "phoneIndex",
        )

        /** Field names that carry a subscription id, which maps to a slot. */
        private val SUBSCRIPTION_FIELDS = listOf(
            "mSubId", "subId", "mSubscriptionId", "subscriptionId",
        )

        private val SLOT_ACCESSORS = listOf("getSlotId", "getSlotIndex", "getPhoneId")

        /**
         * Controllers already served. Weak keys, so a destroyed keyguard does not
         * leak, and static so a hot reload does not reset the guard and submit a
         * second PIN for the same resume.
         */
        private val servedControllers: MutableSet<Any> = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        )

        /** Controllers that appeared while the device was still locked (BFU). */
        private val deferredControllers =
            Collections.synchronizedList(ArrayList<WeakReference<Any>>())

        /** Static so a hot reload does not register the receiver twice. */
        @Volatile
        private var unlockReceiverRegistered = false

        /**
         * Slots whose PIN was already submitted during this boot.
         *
         * Right after boot ColorOS can build a SIM PIN screen before telephony
         * noticed that the card is usable, so the same slot shows up again. Keeping
         * this set stops a second submission into a card that no longer needs it.
         */
        private val unlockedSlots: MutableSet<Int> =
            Collections.synchronizedSet(LinkedHashSet<Int>())

        /** Per-controller counters while telephony is still settling. */
        private val stateRetries: MutableMap<Any, Int> =
            Collections.synchronizedMap(IdentityHashMap<Any, Int>())

        /** Guards the single screen-off/on resync allowed per boot. */
        private val healedThisBoot = AtomicBoolean(false)

        /** How often a not-yet-decided SIM state is re-checked, and how far apart. */
        private const val MAX_STATE_RETRIES = 8
        private const val STATE_RETRY_DELAY_MS = 400L

        /**
         * Delay before an already unlocked card's stale PIN screen is forced to
         * re-read telephony state. Long enough for the submit path to finish and
         * for Keyguard to tear the screen down by itself when it is in sync.
         */
        private const val HEAL_DELAY_MS = 800L
    }

    private var systemUiClassLoader: ClassLoader? = null

    /** The single installed hook, kept so a hot reload can replace it. */
    private var installed: XposedInterface.HookHandle? = null

    @Volatile
    private var context: Context? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        moduleLog(
            this,
            Log.INFO,
            "module loaded: process=${param.processName} systemServer=${param.isSystemServer} " +
                "api=$apiVersion framework=$frameworkName/$frameworkVersion " +
                "props=0x${frameworkProperties.toString(16)}"
        )
    }

    /**
     * Both `onPackageLoaded` and `onPackageReady` fire for the scoped package, so
     * exactly one of them installs the hook; [installHook] is idempotent.
     */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        // `PackageLoadedParam` only exposes `defaultClassLoader`; `classLoader`
        // belongs to `PackageReadyParam`. Either one resolves the host classes.
        @Suppress("USELESS_ELVIS")
        systemUiClassLoader = param.defaultClassLoader ?: systemUiClassLoader
        installHook()
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        systemUiClassLoader = param.classLoader
        moduleLog(this, Log.INFO, "target package ready: ${param.packageName}")
        installHook()
        ensureUnlockReceiver(obtainContext())
    }

    /**
     * Hot reload entry point (API 102).
     *
     * A reload instantiates a **new** module generation and the framework does not
     * replay the package callbacks, so the hook is replaced through the handles
     * handed over instead of being re-installed from scratch.
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        if (apiVersion < 102L) return
        // The new generation must see the newest settings, but the already-served
        // controllers must stay served: resetting that guard could submit a second
        // PIN for one resume and burn a retry out of the SIM's three.
        RemotePrefs.invalidate()

        val config = RemotePrefs.read(this)
        var replaced = 0
        for (handle in param.oldHookHandles) {
            val matches = handle.id == HOOK_ID ||
                handle.executable.name == CONTROLLER_METHOD
            if (!matches) continue
            try {
                handle.replaceHook { chain ->
                    val result = chain.proceed()
                    safely { afterOnResume(chain.thisObject) }
                    result
                }
                replaced++
            } catch (t: Throwable) {
                moduleLog(
                    this,
                    Log.ERROR,
                    "replaceHook failed: ${t.javaClass.name}: ${t.message}"
                )
            }
        }
        moduleLog(
            this,
            Log.INFO,
            "hot reloaded: replaced $replaced hook(s), " +
                "slot1=${if (config.slot1 != null) "set" else "unset"} " +
                "slot2=${if (config.slot2 != null) "set" else "unset"} " +
                "allowBfu=${config.allowBfu}"
        )
    }

    // ------------------------------------------------------------------ hook installation

    private fun installHook() {
        if (installed != null) return

        val loader = systemUiClassLoader ?: run {
            moduleLog(this, Log.INFO, "no class loader captured yet")
            return
        }

        val method = try {
            Class.forName(CONTROLLER_CLASS, false, loader)
                .getDeclaredMethod(CONTROLLER_METHOD, Integer.TYPE)
        } catch (t: Throwable) {
            moduleLog(
                this,
                Log.WARN,
                "cannot resolve $CONTROLLER_CLASS#$CONTROLLER_METHOD: " +
                    "${t.javaClass.name}: ${t.message}"
            )
            return
        }

        try {
            method.isAccessible = true
            val builder = hook(method)
            // Hook ids (atomic replacement on reload) are an API 102 feature.
            if (apiVersion >= 102L) {
                builder.setId(HOOK_ID)
            }
            installed = builder.intercept { chain ->
                // The original return value must be passed through unchanged: for a
                // non-void method the framework converts whatever the hook returns.
                val result = chain.proceed()
                safely { afterOnResume(chain.thisObject) }
                result
            }
            moduleLog(this, Log.INFO, "installed $HOOK_ID on ${method.toGenericString()}")
        } catch (t: Throwable) {
            moduleLog(
                this,
                Log.ERROR,
                "failed to install hook on ${method.toGenericString()}: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    // ------------------------------------------------------------------ hook body

    private fun afterOnResume(controller: Any?) {
        if (controller == null) return
        if (!controller.javaClass.name.endsWith("SimPinViewController")) return

        val config = RemotePrefs.read(this)
        val hostContext = obtainContext()
        val unlocked = isUserUnlocked(hostContext)

        moduleLog(
            this,
            Log.INFO,
            "SIM PIN screen resumed: ${controller.javaClass.name} " +
                "unlocked=$unlocked allowBfu=${config.allowBfu} " +
                "slot1=${if (config.slot1 != null) "set" else "unset"} " +
                "slot2=${if (config.slot2 != null) "set" else "unset"}"
        )

        if (!config.hasAnyPin) {
            moduleLog(this, Log.WARN, "no PIN configured, nothing to do")
            return
        }

        if (!unlocked && !config.allowBfu) {
            // BFU: record the resume, and wait for the user to unlock the device.
            rememberDeferred(controller)
            ensureUnlockReceiver(hostContext)
            moduleLog(
                this,
                Log.INFO,
                "device is in BFU; SIM PIN deferred until the first device unlock " +
                    "(enable the switch in the app to restore BFU unlock)"
            )
            return
        }

        dispatchWithStateGate(controller, config)
    }

    /**
     * Decides whether the card still needs a PIN before touching it.
     *
     * Right after boot SystemUI can rebuild the SIM PIN screen for a card that was
     * already unblocked a few hundred milliseconds earlier: telephony has not
     * published the new state yet, so the screen is shown again for a card that is
     * READY. Submitting there is pointless and leaves a dead screen behind, so the
     * screen is healed instead of filled. Conversely, while the modem is still
     * initialising there is no state to act on at all, and the attempt is retried
     * after a short delay rather than abandoned.
     */
    private fun dispatchWithStateGate(controller: Any, config: PinConfig) {
        val slot = resolveSlot(controller)
        val state = simStateOf(slot)

        val alreadyUnlocked = slot != null &&
            unlockedSlots.contains(slot) &&
            state != TelephonyManager.SIM_STATE_PIN_REQUIRED
        if (alreadyUnlocked) {
            moduleLog(
                this,
                Log.INFO,
                "slot ${slot + 1} already unlocked (state=${simStateName(state)}); " +
                    "healing the stale screen instead of submitting again"
            )
            healStaleScreen(controller)
            return
        }

        when (state) {
            TelephonyManager.SIM_STATE_READY -> {
                if (slot != null) unlockedSlots.add(slot)
                moduleLog(
                    this,
                    Log.INFO,
                    "slot ${slot?.plus(1) ?: "unknown"} is READY; nothing to submit"
                )
                healStaleScreen(controller)
            }

            TelephonyManager.SIM_STATE_UNKNOWN,
            TelephonyManager.SIM_STATE_NOT_READY,
            TelephonyManager.SIM_STATE_ABSENT -> {
                moduleLog(
                    this,
                    Log.INFO,
                    "slot ${slot?.plus(1) ?: "unknown"} state=${simStateName(state)}; " +
                        "waiting for telephony before touching the card"
                )
                scheduleStateRetry(controller)
            }

            else -> dispatch(controller, config)
        }
    }

    /** @return the telephony SIM state for [slot], or `null` when it cannot be read. */
    private fun simStateOf(slot: Int?): Int? {
        if (slot == null) return null
        val hostContext = obtainContext() ?: return null
        return try {
            hostContext.getSystemService(TelephonyManager::class.java)?.getSimState(slot)
        } catch (t: Throwable) {
            moduleLog(this, Log.WARN, "getSimState($slot) failed: ${t.javaClass.name}")
            null
        }
    }

    private fun simStateName(state: Int?): String = when (state) {
        null -> "unknown"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        else -> state.toString()
    }

    /**
     * Retries [afterOnResume] a bounded number of times while the modem has not
     * published a usable state yet. A bounded retry keeps a card that never becomes
     * visible from spinning forever.
     */
    private fun scheduleStateRetry(controller: Any) {
        val attempt = synchronized(stateRetries) {
            val next = (stateRetries[controller] ?: 0) + 1
            stateRetries[controller] = next
            next
        }
        if (attempt > MAX_STATE_RETRIES) {
            stateRetries.remove(controller)
            moduleLog(
                this,
                Log.WARN,
                "telephony state still unavailable after $MAX_STATE_RETRIES retries; " +
                    "leaving the card untouched"
            )
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            safely { afterOnResume(controller) }
        }, STATE_RETRY_DELAY_MS)
    }

    /**
     * Heals the case where SystemUI still shows a SIM PIN page for a card that is
     * already unblocked: the state change is re-broadcast so the keyguard relayouts,
     * and if the screen survives that, one screen off/on cycle is performed, which
     * is exactly what a user does by hand. The power cycle runs at most once per
     * boot so a stuck screen cannot trap the device in a sleep loop.
     */
    private fun healStaleScreen(controller: Any) {
        val view = readField(controller, "mView") as? View ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            safely {
                if (!view.isAttachedToWindow) return@safely
                if (!healedThisBoot.compareAndSet(false, true)) return@safely
                val hostContext = obtainContext() ?: return@safely
                val power = hostContext.getSystemService(PowerManager::class.java)
                    ?: return@safely
                moduleLog(
                    this,
                    Log.INFO,
                    "stale SIM page survived the state refresh; cycling the screen once"
                )
                callPowerMethod(power, "goToSleep")
                Handler(Looper.getMainLooper()).postDelayed({
                    safely {
                        callPowerMethod(power, "wakeUp")
                        moduleLog(this, Log.INFO, "screen cycled to clear the stale SIM page")
                    }
                }, 600L)
            }
        }, HEAL_DELAY_MS)
    }

    /**
     * Calls a hidden `PowerManager` method by trying the known signatures.
     *
     * SystemUI runs with `DEVICE_POWER`, so this is permitted there; everywhere else
     * it simply throws and is logged.
     */
    private fun callPowerMethod(power: PowerManager, name: String) {
        val time = SystemClock.uptimeMillis()
        val candidates = listOf(
            arrayOf<Class<*>>(java.lang.Long.TYPE),
            arrayOf<Class<*>>(java.lang.Long.TYPE, Integer.TYPE),
            arrayOf<Class<*>>(java.lang.Long.TYPE, String::class.java)
        )
        for (types in candidates) {
            try {
                val method = power.javaClass.getMethod(name, *types)
                when (types.size) {
                    1 -> method.invoke(power, time)
                    2 -> method.invoke(
                        power,
                        time,
                        if (types[1] == Integer.TYPE) 0 else "oautopin"
                    )
                }
                return
            } catch (_: NoSuchMethodException) {
                // try the next signature
            } catch (t: Throwable) {
                moduleLog(
                    this,
                    Log.WARN,
                    "$name failed: ${t.javaClass.name}: ${t.message}"
                )
                return
            }
        }
        moduleLog(this, Log.WARN, "$name not available on this build")
    }

    /** Submits the PIN for one controller, at most once per controller instance. */
    private fun dispatch(controller: Any, config: PinConfig) {
        if (!servedControllers.add(controller)) {
            moduleLog(this, Log.INFO, "controller already served, skip")
            return
        }

        val slot = resolveSlot(controller)
        val pin = config.pinForSlot(slot)
        if (pin == null) {
            // Two different PINs configured and the slot is unknown: guessing would
            // burn one of the SIM's three retries, so refuse.
            servedControllers.remove(controller)
            moduleLog(
                this,
                Log.ERROR,
                "cannot decide which PIN to submit (slot unknown and the two cards " +
                    "have different PINs); set the same PIN on both fields or report " +
                    "the field names below"
            )
            describeFields(controller)
            return
        }

        val view = readField(controller, "mView") as? View
        if (view == null) {
            servedControllers.remove(controller)
            moduleLog(this, Log.WARN, "mView unavailable on ${controller.javaClass.name}")
            describeFields(controller)
            return
        }

        moduleLog(
            this,
            Log.INFO,
            "dispatching PIN for slot ${slot?.plus(1) ?: "unknown"}"
        )
        view.post { tryAutoVerify(controller, view, pin, slot) }
    }

    private fun tryAutoVerify(controller: Any, view: View, pin: String, slot: Int?) {
        try {
            if (readField(controller, "isChecking") as? Boolean == true) {
                moduleLog(this, Log.INFO, "isChecking=true, skip")
                return
            }

            val editWidget = invokeMethod(view, "getMEditInputWidget") ?: run {
                moduleLog(this, Log.WARN, "getMEditInputWidget returned null")
                return
            }
            val editText = invokeMethod(editWidget, "getSecurityEditText") as? EditText ?: run {
                moduleLog(this, Log.WARN, "getSecurityEditText returned null")
                return
            }

            editText.setText(pin)
            invokeMethod(controller, "checkSimPikOrPuk")
            if (slot != null) unlockedSlots.add(slot)

            moduleLog(this, Log.INFO, "auto verification dispatched successfully")
        } catch (t: Throwable) {
            moduleLog(this, Log.WARN, "tryAutoVerify error: ${t.javaClass.name}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ AFU gate

    /**
     * @return whether the user has unlocked the device since boot. Unknown counts
     *   as locked: refusing to submit costs one manual PIN entry, submitting too
     *   early costs the guarantee the gate exists for.
     */
    private fun isUserUnlocked(hostContext: Context?): Boolean {
        if (hostContext == null) {
            moduleLog(this, Log.WARN, "no context, treating the device as locked")
            return false
        }
        return try {
            val userManager = hostContext.getSystemService(UserManager::class.java)
            val unlocked = userManager?.isUserUnlocked ?: false
            if (!unlocked) {
                moduleLog(this, Log.INFO, "UserManager.isUserUnlocked()=false")
            }
            unlocked
        } catch (t: Throwable) {
            moduleLog(
                this,
                Log.ERROR,
                "isUserUnlocked failed: ${t.javaClass.name}: ${t.message}"
            )
            false
        }
    }

    private fun rememberDeferred(controller: Any) {
        synchronized(deferredControllers) {
            deferredControllers.removeAll { it.get() == null || it.get() === controller }
            deferredControllers.add(WeakReference(controller))
        }
    }

    /**
     * Runs once the user has unlocked the device.
     *
     * If the SIM PIN screen from BFU is still on screen it is served directly;
     * otherwise (the usual case, the screen was dismissed so the password could be
     * typed) the PIN is submitted to telephony for every still-locked slot.
     */
    private fun onDeviceUnlocked() {
        moduleLog(this, Log.INFO, "device unlocked: settling the SIM PIN state")
        val config = RemotePrefs.read(this)
        if (!config.hasAnyPin) {
            moduleLog(this, Log.WARN, "device unlocked, but no PIN is configured")
            return
        }

        var handled = 0
        synchronized(deferredControllers) {
            val iterator = deferredControllers.iterator()
            while (iterator.hasNext()) {
                val controller = iterator.next().get()
                if (controller == null) {
                    iterator.remove()
                    continue
                }
                val view = readField(controller, "mView") as? View
                if (view != null && view.isAttachedToWindow) {
                    iterator.remove()
                    dispatch(controller, config)
                    handled++
                }
            }
        }

        if (handled == 0) {
            supplyPinsDirectly(obtainContext(), config)
        }
    }

    /**
     * Fallback for the common case where the BFU prompt is gone by the time the
     * user finishes unlocking: ask telephony to verify the PIN without any UI.
     *
     * Requires `MODIFY_PHONE_STATE`, which SystemUI holds on the tested ROMs; when
     * it is missing the attempt is logged and the user still gets the manual
     * prompt, so this can never make things worse.
     */
    private fun supplyPinsDirectly(hostContext: Context?, config: PinConfig) {
        if (hostContext == null) {
            moduleLog(this, Log.WARN, "no context, cannot supply the PIN directly")
            return
        }
        val telephony = hostContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephony == null) {
            moduleLog(this, Log.WARN, "TelephonyManager unavailable")
            return
        }

        val locked = lockedSlots(telephony)
        if (locked.isEmpty()) {
            moduleLog(this, Log.INFO, "no SIM reports PIN_REQUIRED, nothing to unlock")
            return
        }

        for (slot in locked) {
            val pin = config.pinForSlot(slot)
            if (pin == null) {
                moduleLog(this, Log.WARN, "slot ${slot + 1} is locked but has no PIN")
                continue
            }
            val accepted = supplyPin(hostContext, telephony, slot, pin)
            moduleLog(
                this,
                if (accepted) Log.INFO else Log.WARN,
                "slot ${slot + 1}: supplyPin ${if (accepted) "sent" else "failed"}"
            )
        }
    }

    /** 0-based slots whose SIM currently asks for a PIN. */
    private fun lockedSlots(telephony: TelephonyManager): List<Int> {
        val locked = ArrayList<Int>(2)
        for (slot in 0..1) {
            val state = try {
                telephony.getSimState(slot)
            } catch (t: Throwable) {
                moduleLog(this, Log.WARN, "getSimState($slot) failed: ${t.message}")
                continue
            }
            if (state == TelephonyManager.SIM_STATE_PIN_REQUIRED) locked.add(slot)
        }
        return locked
    }

    private fun supplyPin(
        hostContext: Context,
        telephony: TelephonyManager,
        slot: Int,
        pin: String,
    ): Boolean {
        // Public/hidden TelephonyManager entry points, newest first.
        val candidates = listOf(
            "supplyPin" to arrayOf<Class<*>>(Integer.TYPE, String::class.java),
            "supplyPin" to arrayOf<Class<*>>(String::class.java),
        )
        for ((name, types) in candidates) {
            try {
                val method = telephony.javaClass.getMethod(name, *types)
                method.isAccessible = true
                if (types.size == 2) {
                    method.invoke(telephony, slot, pin)
                } else {
                    method.invoke(telephony, pin)
                }
                return true
            } catch (_: NoSuchMethodException) {
                // Try the next signature.
            } catch (t: Throwable) {
                moduleLog(
                    this,
                    Log.WARN,
                    "$name(${types.joinToString { it.simpleName }}) failed: " +
                        "${t.javaClass.name}: ${t.message}"
                )
            }
        }

        // Older path: resolve the subscription id of the slot first.
        val subId = subscriptionIdForSlot(hostContext, slot) ?: return false
        return try {
            val method = telephony.javaClass.getMethod(
                "supplyPinForSubscriber",
                Integer.TYPE,
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(telephony, subId, pin)
            true
        } catch (t: Throwable) {
            moduleLog(
                this,
                Log.WARN,
                "supplyPinForSubscriber($subId) failed: " +
                    "${t.javaClass.name}: ${t.message}"
            )
            false
        }
    }

    private fun subscriptionIdForSlot(hostContext: Context, slot: Int): Int? = try {
        val manager = hostContext.getSystemService(SubscriptionManager::class.java)
        manager?.getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId
            ?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    } catch (t: Throwable) {
        moduleLog(this, Log.WARN, "subscription lookup failed: ${t.message}")
        null
    }

    private fun ensureUnlockReceiver(hostContext: Context?) {
        if (hostContext == null || unlockReceiverRegistered) return
        synchronized(Hook::class.java) {
            if (unlockReceiverRegistered) return
            try {
                hostContext.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(receiverContext: Context?, intent: Intent?) {
                            if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                                safely { onDeviceUnlocked() }
                            }
                        }
                    },
                    IntentFilter(Intent.ACTION_USER_UNLOCKED),
                )
                unlockReceiverRegistered = true
                moduleLog(this, Log.INFO, "registered ACTION_USER_UNLOCKED receiver")
            } catch (t: Throwable) {
                moduleLog(
                    this,
                    Log.ERROR,
                    "registerReceiver failed: ${t.javaClass.name}: ${t.message}"
                )
            }
        }
    }

    private fun obtainContext(): Context? {
        context?.let { return it }
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass
                .getMethod("currentActivityThread")
                .invoke(null) ?: return null
            val application = try {
                activityThreadClass.getMethod("getApplication").invoke(activityThread) as? Context
            } catch (_: Throwable) {
                null
            }
            val resolved = application ?: try {
                activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as? Context
            } catch (_: Throwable) {
                null
            }
            resolved?.also { context = it }
        } catch (t: Throwable) {
            moduleLog(this, Log.WARN, "cannot obtain a context: ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------------ slot resolution

    /**
     * @return the 0-based card slot of [controller], or `null` when it cannot be
     *   determined with confidence.
     */
    private fun resolveSlot(controller: Any): Int? {
        for (name in SLOT_FIELDS) {
            val value = readField(controller, name) as? Int ?: continue
            if (value in 0..1) {
                moduleLog(this, Log.INFO, "slot resolved from $name=$value")
                return value
            }
        }

        for (name in SUBSCRIPTION_FIELDS) {
            val subId = readField(controller, name) as? Int ?: continue
            if (subId <= 0) continue
            slotForSubscription(subId)?.let {
                moduleLog(this, Log.INFO, "slot resolved from $name=$subId -> $it")
                return it
            }
        }

        for (name in SLOT_ACCESSORS) {
            val value = invokeMethod(controller, name) as? Int ?: continue
            if (value in 0..1) {
                moduleLog(this, Log.INFO, "slot resolved from $name()=$value")
                return value
            }
        }

        // Last resort: exactly one card is asking for a PIN, so it must be that one.
        val hostContext = obtainContext()
        val telephony = hostContext?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephony != null) {
            val locked = lockedSlots(telephony)
            if (locked.size == 1) {
                moduleLog(this, Log.INFO, "slot resolved from telephony: only ${locked[0] + 1} is locked")
                return locked[0]
            }
        }

        moduleLog(this, Log.WARN, "cannot resolve the card slot of ${controller.javaClass.name}")
        return null
    }

    private fun slotForSubscription(subId: Int): Int? = try {
        val hostContext = obtainContext()
        val manager = hostContext?.getSystemService(SubscriptionManager::class.java)
        // SubscriptionManager.getSlotIndex(int) is a hidden @SystemApi and is not
        // part of the public SDK this module compiles against, so resolve the slot
        // through the public subscription list instead.
        manager?.activeSubscriptionInfoList
            ?.firstOrNull { it.subscriptionId == subId }
            ?.simSlotIndex
            ?.takeIf { it >= 0 }
    } catch (t: Throwable) {
        moduleLog(this, Log.WARN, "subscription lookup($subId) failed: ${t.message}")
        null
    }

    /** Logs the controller's field shape once, so an unknown ROM can be diagnosed. */
    private fun describeFields(controller: Any) {
        try {
            val builder = StringBuilder("fields of ${controller.javaClass.name}: ")
            var type: Class<*>? = controller.javaClass
            var count = 0
            while (type != null && count < 60) {
                for (field in type.declaredFields) {
                    if (count++ >= 60) break
                    val value = try {
                        field.isAccessible = true
                        when (field.type) {
                            Integer.TYPE, java.lang.Boolean.TYPE, String::class.java ->
                                "${field.name}=${field.get(controller)}"
                            else -> null
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    if (value != null) builder.append(value).append(", ")
                }
                type = type.superclass
            }
            moduleLog(this, Log.INFO, builder.toString())
        } catch (t: Throwable) {
            moduleLog(this, Log.WARN, "describeFields failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    /** Walks the superclass chain, because Oplus hides the field in a base class. */
    private fun readField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (t: Throwable) {
                moduleLog(this, Log.INFO, "readField($name) failed: ${t.javaClass.name}: ${t.message}")
                return null
            }
        }
        return null
    }

    private fun invokeMethod(target: Any, name: String, vararg args: Any?): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            }
            if (method != null) {
                return try {
                    method.isAccessible = true
                    method.invoke(target, *args)
                } catch (t: Throwable) {
                    moduleLog(
                        this,
                        Log.INFO,
                        "invokeMethod($name) failed: ${t.javaClass.name}: ${t.message}"
                    )
                    null
                }
            }
            type = type.superclass
        }
        moduleLog(this, Log.INFO, "invokeMethod($name) not found on ${target.javaClass.name}")
        return null
    }

    /** Runs [block], swallowing anything: a hook body must never crash SystemUI. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            moduleLog(this, Log.ERROR, "hook body failed: ${t.javaClass.name}: ${t.message}")
        }
    }
}
