package io.github.achyuki.oautopin

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/**
 * Configuration UI.
 *
 * Values are written into framework-owned RemotePreferences (see [PinStore]), not
 * into a world-readable preference file: the hooked `com.android.systemui` process
 * reads the same store through [RemotePrefs], so the PIN never lands anywhere that
 * is readable before the first device unlock.
 *
 * Two PINs are supported because a dual-SIM device can carry two locked cards.
 *
 * Every [PinStore] call runs on a worker thread: binding the framework service can
 * block for a few seconds, which must not happen on the main thread.
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "oautopin-prefs").apply { isDaemon = true }
    }

    private lateinit var rootView: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var slot1Edit: EditText
    private lateinit var slot2Edit: EditText
    private lateinit var allowBfuCheck: CheckBox
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)

        val scrollRoot = ScrollView(this)
        rootView = LinearLayout(this)
        scrollRoot.addView(rootView)
        rootView.orientation = LinearLayout.VERTICAL
        setContentView(scrollRoot)

        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        buildUi()
        // Bind as early as possible: the framework starts the service when this
        // module's own process starts.
        PinStore.bind()
        loadConfig()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        rootView.setPadding(dp(20), dp(20), dp(20), dp(20))

        rootView.addView(
            caption(
                "开机后自动输入 SIM 卡 PIN。\n\n" +
                    "默认只在设备被密码/指纹解锁之后才解锁 SIM；重启后停留在 BFU" +
                    "（尚未解锁设备）时不会自动填 PIN，捡到手机的人拿不到蜂窝网络。"
            )
        )

        rootView.addView(label("卡1 PIN（4-8 位数字）"))
        slot1Edit = pinField("卡1 PIN")
        rootView.addView(slot1Edit)

        rootView.addView(label("卡2 PIN（可选，留空表示与卡1相同）"))
        slot2Edit = pinField("卡2 PIN")
        rootView.addView(slot2Edit)

        allowBfuCheck = CheckBox(this)
        allowBfuCheck.text = "允许在 BFU 阶段（尚未解锁设备）自动解锁 SIM"
        rootView.addView(allowBfuCheck)

        rootView.addView(
            caption(
                "开启这项会回到原版行为：重启后即使没人解锁设备，SIM 也会被解锁，" +
                    "蜂窝定位因此可用。代价是这段时间短信与数据通路对所有持有手机的人" +
                    "开放。若你已解锁 Bootloader，防盗本身已经没有意义，建议保持关闭。"
            )
        )

        saveButton = Button(this)
        saveButton.text = "保存"
        saveButton.setOnClickListener { save() }
        rootView.addView(saveButton)

        statusView = TextView(this)
        statusView.setPadding(0, dp(12), 0, 0)
        rootView.addView(statusView)

        setBusy(true)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(8), 0, 0)
    }

    private fun pinField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    // ------------------------------------------------------------------ loading

    private fun loadConfig() {
        showStatus("正在连接 LSPosed 配置服务…", false)
        worker.execute {
            val config = PinStore.readConfig()
            val error = PinStore.lastError
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                slot1Edit.setText(config.slot1.orEmpty())
                slot2Edit.setText(config.slot2.orEmpty())
                allowBfuCheck.isChecked = config.allowBfu
                if (config.hasAnyPin) {
                    showStatus("已读取当前配置。", false)
                } else {
                    showStatus(
                        error ?: "还没有保存过 PIN，填写后点“保存”。",
                        error != null
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private fun save() {
        val rawSlot1 = slot1Edit.text.toString()
        val rawSlot2 = slot2Edit.text.toString()
        val slot1 = PinConfig.sanitize(rawSlot1)
        val slot2 = PinConfig.sanitize(rawSlot2)

        if (rawSlot1.isNotBlank() && slot1 == null) {
            Toast.makeText(this, "卡1 PIN 必须是 4-8 位数字", Toast.LENGTH_SHORT).show()
            showStatus("卡1 PIN 必须是 4-8 位数字。", true)
            return
        }
        if (rawSlot2.isNotBlank() && slot2 == null) {
            Toast.makeText(this, "卡2 PIN 必须是 4-8 位数字", Toast.LENGTH_SHORT).show()
            showStatus("卡2 PIN 必须是 4-8 位数字。", true)
            return
        }
        if (slot1 == null && slot2 == null) {
            Toast.makeText(this, "至少填写卡1 PIN", Toast.LENGTH_SHORT).show()
            showStatus("至少填写卡1 PIN。", true)
            return
        }

        val config = PinConfig(slot1 = slot1, slot2 = slot2, allowBfu = allowBfuCheck.isChecked)
        setBusy(true)
        showStatus("正在保存…", false)
        worker.execute {
            val saved = PinStore.writeConfig(config)
            val error = PinStore.lastError
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (saved) {
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                    showStatus(
                        "已写入 LSPosed 配置。模块支持热重载，通常立即生效；" +
                            "未生效时重启 SystemUI 或重启手机。",
                        false
                    )
                } else {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                    showStatus("保存失败：${error ?: "未知错误"}", true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun setBusy(busy: Boolean) {
        saveButton.isEnabled = !busy
        slot1Edit.isEnabled = !busy
        slot2Edit.isEnabled = !busy
        allowBfuCheck.isEnabled = !busy
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusView.text = message
        statusView.setTextColor(if (isError) ERROR_COLOR else NORMAL_COLOR)
        statusView.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ERROR_COLOR = 0xFFD32F2F.toInt()
        const val NORMAL_COLOR = 0xFF2E7D32.toInt()
    }
}
