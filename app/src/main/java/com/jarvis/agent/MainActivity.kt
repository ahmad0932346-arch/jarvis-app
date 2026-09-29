package com.jarvis.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Settings + permissions screen. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var geminiKey: EditText
    private lateinit var picoKey: EditText
    private lateinit var model: EditText
    private lateinit var speechLang: EditText
    private lateinit var replyLang: EditText
    private lateinit var testCmd: EditText

    private var pendingStart = false
    private var pendingGoal: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 24f
        }.also { root.addView(it) }

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setPadding(0, dp(14), 0, 0)
        }.also { root.addView(it) }

        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        }.also { root.addView(it) }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }.also { root.addView(it) }

        title("Jarvis")
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)

        label("Gemini API key (aistudio.google.com/apikey)")
        geminiKey = field("AIza…", Prefs.get(this, Prefs.GEMINI_KEY), true)
        label("Picovoice AccessKey for the wake word (console.picovoice.ai)")
        picoKey = field("Picovoice AccessKey", Prefs.get(this, Prefs.PICO_KEY), true)
        label("Gemini model")
        model = field("gemini-2.5-flash", Prefs.get(this, Prefs.MODEL, "gemini-2.5-flash"))
        label("Speech recognition language (en-US, si-LK, ta-LK …)")
        speechLang = field("en-US", Prefs.get(this, Prefs.LANG, "en-US"))
        label("Reply language (English, Singlish (Sinhala in English letters), Sinhala …)")
        replyLang = field("English", Prefs.get(this, Prefs.REPLY_LANG, "English"))

        button("Save settings") { save(); toast("Saved") }

        label("Setup steps")
        button("1. Enable Accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Allow \"Display over other apps\"") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        button("3. Start Jarvis (wake word)") { start(null) }
        button("Stop Jarvis") { stopService(Intent(this, WakeService::class.java)); refresh() }
        button("Battery: allow background use") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        label("Test without the wake word")
        testCmd = field("e.g. open YouTube and search lofi music", "")
        button("Run command") {
            val g = testCmd.text.toString().trim()
            if (g.isNotEmpty()) start(g)
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val a11y = JarvisAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        status.text = "Accessibility: ${if (a11y) "✅ on" else "❌ off"}\n" +
            "Overlay permission: ${if (overlay) "✅" else "❌ (needed to launch apps from background)"}\n" +
            "Microphone: ${if (mic) "✅" else "❌"}\n" +
            "Jarvis service: ${if (WakeService.running) "✅ running" else "⏹ stopped"}"
    }

    private fun save() {
        Prefs.put(this, Prefs.GEMINI_KEY, geminiKey.text.toString().trim())
        Prefs.put(this, Prefs.PICO_KEY, picoKey.text.toString().trim())
        Prefs.put(this, Prefs.MODEL, model.text.toString().trim().ifEmpty { "gemini-2.5-flash" })
        Prefs.put(this, Prefs.LANG, speechLang.text.toString().trim().ifEmpty { "en-US" })
        Prefs.put(this, Prefs.REPLY_LANG, replyLang.text.toString().trim().ifEmpty { "English" })
    }

    private fun start(goal: String?) {
        save()
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.CALL_PHONE
        }
        if (need.isNotEmpty()) {
            pendingStart = true
            pendingGoal = goal
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        launchService(goal)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!pendingStart) return
        pendingStart = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchService(pendingGoal)
        } else {
            toast("Microphone permission is required")
        }
        pendingGoal = null
    }

    private fun launchService(goal: String?) {
        val i = Intent(this, WakeService::class.java)
        if (goal != null) {
            i.action = WakeService.ACTION_RUN
            i.putExtra(WakeService.EXTRA_GOAL, goal)
        }
        startForegroundService(i)
        window.decorView.postDelayed({ refresh() }, 600)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
