package io.github.achyuki.oautopin

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView;
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    private lateinit var rootView: LinearLayout
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)

        val scrollRoot = ScrollView(this)
        rootView = LinearLayout(this)
        scrollRoot.addView(rootView)
        rootView.orientation = LinearLayout.VERTICAL
        setContentView(scrollRoot)

        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        try {
            prefs = getSharedPreferences("pin", MODE_WORLD_READABLE)
            setupUI()
        } catch (e: SecurityException) {
            // The new XSharedPreferences is not enabled or module's not loading
            val textView = TextView(this);
            textView.text = "Error: Module not loaded"
            rootView.addView(textView);
        }
    }

    private fun setupUI() {
        val pinEditText = EditText(this)
        pinEditText.hint = "Input PIN (4-8 NUM)"
        pinEditText.filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        rootView.addView(pinEditText)

        val saveButton = Button(this)
        saveButton.text = "Save"
        rootView.addView(saveButton)

        saveButton.setOnClickListener {
            val pin = pinEditText.text.toString().trim()
            if (!pin.matches(Regex("^\\d{4,8}$"))) {
                Toast.makeText(this@MainActivity, "Input invalid", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("pin", pin).apply()
            Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
        }
    }
}
