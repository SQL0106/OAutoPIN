package io.github.achyuki.oautopin

import android.widget.EditText
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Hook : IXposedHookLoadPackage {
    companion object {
        private lateinit var xsp: XSharedPreferences
        private var autoTried = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        xsp = XSharedPreferences("io.github.achyuki.oautopin", "pin")
        xsp.reload()
        val pin = xsp.getString("pin", "")
        if (pin.isNullOrBlank() || !pin.matches(Regex("^\\d{4,8}$"))) {
            XposedBridge.log("[OAutoPIN] PIN invalid or empty, auto verify disabled")
            return
        }

        XposedHelpers.findAndHookMethod(
        "com.oplus.keyguard.security.view.OplusKeyguardSimInputViewController",
        lpparam.classLoader,
        "onResume",
        Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val controller = param.thisObject

                if (!controller.javaClass.name.endsWith("SimPinViewController")) return
                if (autoTried) return

                val mView = XposedHelpers.getObjectField(controller, "mView") as android.view.View
                mView.post { tryAutoVerify(controller, mView, pin) }
            }
        })
    }

    private fun tryAutoVerify(controller: Any, mView: Any, pin: String) {
        try {
            val isChecking: Boolean = XposedHelpers.getBooleanField(controller, "isChecking")
            if (isChecking) {
                XposedBridge.log("[OAutoPIN] isChecking=true, skip")
                return
            }

            val editWidget = XposedHelpers.callMethod(mView, "getMEditInputWidget")
                ?: run {
                    XposedBridge.log("[OAutoPIN] getMEditInputWidget returned null")
                    return
                }
            val editText = XposedHelpers.callMethod(editWidget, "getSecurityEditText") as? EditText
                ?: run {
                    XposedBridge.log("[OAutoPIN] getSecurityEditText returned null")
                    return
                }

            autoTried = true
            editText.setText(pin)
            XposedHelpers.callMethod(controller, "checkSimPikOrPuk")

            XposedBridge.log("[OAutoPIN] Auto verification dispatched successfully")
        } catch (e: Exception) {
            XposedBridge.log("[OAutoPIN] tryAutoVerify error: ${e.message}")
        }
    }
}