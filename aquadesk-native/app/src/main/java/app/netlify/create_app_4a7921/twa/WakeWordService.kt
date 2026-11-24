package app.netlify.create_app_4a7921.twa

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Always-listening wake-word detector built on Android's SpeechRecognizer.
 *
 * Flow: listen -> hear wake phrase -> (optional spoken command: "connect to my computer",
 * "open app") -> act. If only the phrase is heard, a short tone plays and the next
 * utterance is treated as the command.
 *
 * Notes: SpeechRecognizer usually needs internet, and Android may beep on every restart;
 * we mute the music stream's beep while listening and restore it on stop.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_STOP = "app.netlify.create_app_4a7921.twa.STOP_WAKE"
        const val ACTION_OPEN_REMOTE = "open_remote"
        const val ACTION_OPEN_APP = "open_app"
        private const val CH_LISTEN = "wake_listen"
        private const val CH_ALERT = "wake_alert"

        /** Set by MainActivity while it is visible so we can act without a notification. */
        @Volatile var foregroundHandler: ((String, String) -> Unit)? = null
        @Volatile var running = false

        fun start(ctx: Context) {
            val i = Intent(ctx, WakeWordService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var idleStreak = 0       // consecutive "heard nothing" cycles -> listen less frantically
    private val recogIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)   // no network round-trip when the phone supports it
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
    }
    private var awaitingCommand = false
    private var stopped = false
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private var muted = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Wake word listening", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Wake word detected", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.wakeEnabled = false
            stopSelf(); return START_NOT_STICKY
        }
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_LISTEN)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AquaDesk is listening")
            .setContentText("Say \"${prefs.wakePhrase}\"")
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        val already = running && recognizer != null    // MainActivity re-sends start on every launch
        running = true; stopped = false
        if (!already) main.post { startListening() }
        return START_STICKY
    }

    private fun muteBeep(on: Boolean) {
        if (on == muted) return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            muted = on
        } catch (_: Exception) {}
    }

    private fun startListening() {
        if (stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.widget.Toast.makeText(this,
                "No speech recognition service on this phone. Install/enable \"Speech Recognition & Synthesis\" (Google) and try again.",
                android.widget.Toast.LENGTH_LONG).show()
            stopSelf(); return
        }
        // Reuse one recognizer instead of destroying/creating it every few seconds (that churn
        // runs on the main thread and is what made the whole app stutter).
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener); recognizer = it
        }
        try { r.startListening(recogIntent) }
        catch (e: Exception) { r.destroy(); recognizer = null; restart(1500); return }
        muteBeep(true)
    }

    private fun restart(delay: Long = 300) {
        if (stopped) return
        main.postDelayed({ startListening() }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(r: android.os.Bundle?) {
            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) idleStreak = 0
            handle(text.lowercase(), final = true)
        }
        override fun onPartialResults(p: android.os.Bundle?) {
            val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (!awaitingCommand) handle(text.lowercase(), final = false)
        }
        override fun onError(error: Int) {
            awaitingCommand = false
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recognizer?.destroy(); recognizer = null; restart(1500)   // only now is a fresh recognizer needed
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> restart(4000)
                else -> { idleStreak++; restart(minOf(300L + idleStreak * 150L, 800L)) }   // silence: back off a little
            }
        }
        override fun onReadyForSpeech(p: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, b: android.os.Bundle?) {}
    }

    private var handledAt = 0L

    private fun handle(text: String, final: Boolean) {
        if (text.isBlank()) { if (final) restart(); return }
        val phrase = prefs.wakePhrase
        if (awaitingCommand) {
            awaitingCommand = false
            runCommand(text); restart(800); return
        }
        val idx = text.indexOf(phrase)
        if (idx < 0) { if (final) restart(); return }
        // Debounce: partials fire repeatedly for the same utterance.
        val now = System.currentTimeMillis()
        if (now - handledAt < 2500) { if (final) restart(); return }
        handledAt = now
        recognizer?.cancel()
        val rest = text.substring(idx + phrase.length).trim()
        if (rest.isNotEmpty()) { runCommand(rest); restart(800) }
        else {
            // Heard only the phrase: chime, then capture one command.
            try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
            awaitingCommand = true
            main.postDelayed({ if (!stopped) startListening() }, 400)
            // If nothing is said, fall back to idle listening.
            main.postDelayed({ awaitingCommand = false }, 8000)
        }
    }

    private fun runCommand(cmd: String) {
        // "open chrome", "close spotify on my computer" -> straight to the PC, no UI needed.
        PcCommand.parse(cmd)?.let { (action, app) ->
            PcCommand.run(prefs, action, app) { ok, msg ->
                main.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
                foregroundHandler?.invoke("pc_$action", "${if (ok) "ok" else "error"}: $msg")
            }
            return
        }
        val remote = listOf("connect", "computer", "desktop", "pc", "remote", "mirror", "screen")
        val action = if (remote.any { cmd.contains(it) }) ACTION_OPEN_REMOTE else ACTION_OPEN_APP
        foregroundHandler?.let { it(action, cmd); return }
        notify(action, cmd)
    }

    /** From the background we can't start activities directly (Android 10+), so use a full-screen-intent notification. */
    private fun notify(action: String, cmd: String) {
        val target = if (action == ACTION_OPEN_REMOTE) RemoteDesktopActivity::class.java else MainActivity::class.java
        val pi = PendingIntent.getActivity(this, 2,
            Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("autoconnect", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (action == ACTION_OPEN_REMOTE) "Connect to your computer" else "Open AquaDesk")
            .setContentText("Heard: \"$cmd\"")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, n)
    }

    override fun onDestroy() {
        stopped = true; running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        muteBeep(false)
        super.onDestroy()
    }
}
