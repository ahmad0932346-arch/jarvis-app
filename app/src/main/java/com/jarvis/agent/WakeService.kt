package com.jarvis.agent

import com.openwakeword.OpenWakeWord
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Always-on foreground service:
 * wake word ("Jarvis") -> beep -> listen -> Agent loop -> speak -> back to wake word.
 */
class WakeService : Service() {

    companion object {
        const val ACTION_RUN = "com.jarvis.agent.RUN"
        const val ACTION_CANCEL = "com.jarvis.agent.CANCEL"
        const val EXTRA_GOAL = "goal"
        private const val CHANNEL = "jarvis"
        private const val NOTIF_ID = 1
        @Volatile var running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeWord: OpenWakeWord? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var job: Job? = null
    private var overlay: TextView? = null
    private var notifText = "Starting…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val reply = Prefs.get(this, Prefs.REPLY_LANG, "English").lowercase()
                val loc = if (reply.contains("sinhala") && !reply.contains("english")) Locale("si", "LK") else Locale.US
                val t = tts
                if (t != null && t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (intent?.action) {
            ACTION_CANCEL -> job?.cancel()
            ACTION_RUN -> intent.getStringExtra(EXTRA_GOAL)?.let { startSession(it) }
            else -> if (wakeWord == null) initWake()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        try { wakeWord?.stop(); wakeWord?.release() } catch (_: Exception) {}
        tts?.shutdown()
        hideOverlay()
        super.onDestroy()
    }

    // ---------- wake word ----------
    private fun initWake() {
    try {
        wakeWord = OpenWakeWord.Builder(this)
            .setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
            .setThreshold(0.5f)
            .build()
wakeWord?.start {
    scope.launch {
        startSession(null)
    }
}

        setNotif("Listening for the wake word \"Hey Jarvis\"")
    } catch (e: Exception) {
        setNotif("Wake word error: ${e.message}")
    }
}

    private fun resumeWake() {
    try { wakeWord?.start { scope.launch { startSession(null) } } } catch (_: Exception) {}
    setNotif("Listening for the wake word \"Hey Jarvis\"")
}

    // ---------- one full interaction ----------
    private fun startSession(typedGoal: String?) {
        if (busy) return
        busy = true
        job = scope.launch {
            try {
                try { wakeWord?.stop() } catch (_: Exception) {}
                delay(300) // let the microphone be released
                var goal = typedGoal
                if (goal == null) {
                    beep()
                    show("🎙 Listening…")
                    goal = listenOnce()
                }
                if (goal.isNullOrBlank()) {
                    show("Didn't catch that")
                    delay(1200)
                } else {
                    show("🧠 $goal")
                    Agent(applicationContext, io).run(goal)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speakOut("Sorry, something went wrong: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    hideOverlay()
                    resumeWake()
                    busy = false
                }
            }
        }
    }

    private val io = object : Agent.AgentIO {
        override fun status(text: String) = show(text)

        override suspend fun speak(text: String) {
            show("🤖 $text")
            speakOut(text)
        }

        override suspend fun confirm(question: String): Boolean {
            show("❓ $question")
            speakOut(question)
            beep()
            val answer = (listenOnce() ?: "").lowercase()
            return Regex("\\b(yes|yeah|yep|ok|okay|sure|confirm|do it|ow|hari)\\b|ඔව්|හරි").containsMatchIn(answer)
        }
    }

    // ---------- speech in / out ----------
    private suspend fun listenOnce(): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(this@WakeService)) {
            speakOut("Speech recognition is not available on this phone.")
            return@withContext null
        }
        val lang = Prefs.get(this@WakeService, Prefs.LANG, "en-US")
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<String?> { cont ->
                val rec = SpeechRecognizer.createSpeechRecognizer(this@WakeService)
                val finished = AtomicBoolean(false)
                fun finish(text: String?) {
                    if (finished.compareAndSet(false, true)) {
                        rec.destroy()
                        if (cont.isActive) cont.resume(text)
                    }
                }
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                    }
                    override fun onError(error: Int) = finish(null)
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                cont.invokeOnCancellation { mainHandler.post { rec.destroy() } }
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                rec.startListening(i)
            }
        }
    }

    private suspend fun speakOut(text: String) = withContext(Dispatchers.Main) {
        val t = tts
        if (!ttsReady || t == null) return@withContext
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val id = UUID.randomUUID().toString()
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                })
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        }
        Unit
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            mainHandler.postDelayed({ tg.release() }, 500)
        } catch (_: Exception) {}
    }

    // ---------- status UI (overlay pill + notification) ----------
    private fun show(msg: String) {
        mainHandler.post { showOverlayNow(msg) }
        setNotif(msg.take(100))
    }

    private fun showOverlayNow(msg: String) {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (overlay == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(36, 18, 36, 18)
                maxLines = 3
                background = GradientDrawable().apply {
                    setColor(0xCC202124.toInt())
                    cornerRadius = 60f
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 90
            }
            try {
                wm.addView(tv, lp)
                overlay = tv
            } catch (_: Exception) {
                return
            }
        }
        overlay?.text = msg
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {}
        overlay = null
    }

    private fun setNotif(text: String) {
        notifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif())
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis")
            .setContentText(notifText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop task", cancel)
            .build()
    }

    private fun startFg() {
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
