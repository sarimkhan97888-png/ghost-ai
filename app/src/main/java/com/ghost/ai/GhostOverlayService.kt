package com.ghost.ai

import android.Manifest
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioManager
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
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.ghost.ai.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that owns the floating GHOST pill, the listening popup, speech
 * recognition, the Gemini decision loop and text-to-speech replies.
 */
class GhostOverlayService : Service() {

    private enum class Phase { IDLE, LISTENING, THINKING, ACTING, REPLY }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gemini = GeminiClient()

    private lateinit var windowManager: WindowManager
    private lateinit var themedInflater: LayoutInflater

    // Pill
    private var pillView: TextView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null

    // Popup
    private var popupView: View? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private val popupAnimators = ArrayList<Animator>()

    // Session
    private var phase = Phase.IDLE
    private var recognizer: SpeechRecognizer? = null
    private var agentJob: Job? = null

    // Text to speech
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Wake word ("Ghost" / "Jarvis")
    private var destroyed = false
    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeTriggered = false
    private var wakeFailures = 0
    private var wakePreferOffline = true
    private val mutedStreams = HashSet<Int>()
    private val startWakeRunnable = Runnable { startWakeListening() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == GhostPrefs.KEY_WAKE) mainHandler.post { onWakePrefChanged() }
    }

    private val startListeningRunnable = Runnable { beginListening() }
    private val closeRunnable = Runnable { endSession() }

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val themeRes: Int = R.style.Theme_GhostAI
        val themedContext: Context = ContextThemeWrapper(this, themeRes)
        themedInflater = LayoutInflater.from(themedContext)
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must happen quickly after startForegroundService().
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is missing", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (pillView == null && !addPill()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        GhostPrefs.unregisterListener(this, prefListener)
        GhostPrefs.registerListener(this, prefListener)
        updateNotification()
        scheduleWake(600L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        GhostPrefs.unregisterListener(this, prefListener)
        stopWake(true)
        endSession()
        snapAnimator?.cancel()
        removeView(pillView)
        pillView = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val notification = buildNotification()
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )

                else -> startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Missing RECORD_AUDIO on Android 14+, or the system refused a background start.
            Toast.makeText(
                this,
                "Could not start Ghost. Open the app and grant the microphone permission.",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GhostOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title: CharSequence = getString(R.string.notif_title)
        val body: CharSequence = getString(
            if (GhostPrefs.isWakeEnabled(this)) R.string.notif_text_wake else R.string.notif_text
        )
        val stopLabel: CharSequence = getString(R.string.notif_action_stop)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_ghost),
            stopLabel,
            stopIntent
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ghost)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    // ------------------------------------------------------------------ pill

    private fun addPill(): Boolean {
        val pill = themedInflater.inflate(R.layout.overlay_pill, null) as TextView
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - dp(120)
            y = dm.heightPixels / 3
        }

        pill.setOnClickListener { onPillTapped() }
        pill.setOnTouchListener(PillTouchListener())

        return try {
            windowManager.addView(pill, params)
            pillView = pill
            pillParams = params
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show the overlay", Toast.LENGTH_LONG).show()
            false
        }
    }

    private inner class PillTouchListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@GhostOverlayService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = pillParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (maxX, maxY) = pillBounds(v)
                        params.x = (startX + dx).toInt().coerceIn(0, maxX)
                        params.y = (startY + dy).toInt().coerceIn(0, maxY)
                        updateLayoutSafely(v, params)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdge(v, params)
                    } else {
                        v.performClick()
                    }
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return false
        }
    }

    private fun pillBounds(pill: View): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val w = max(pill.width, dp(96))
        val h = max(pill.height, dp(44))
        return max(0, dm.widthPixels - w) to max(0, dm.heightPixels - h - dp(72))
    }

    private fun snapToEdge(pill: View, params: WindowManager.LayoutParams) {
        val (maxX, _) = pillBounds(pill)
        val target = if (params.x + pill.width / 2 < resources.displayMetrics.widthPixels / 2) 0 else maxX
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                updateLayoutSafely(pill, params)
            }
            start()
        }
    }

    private fun setPillState(text: String?) {
        pillView?.text = text ?: getString(R.string.pill_label)
    }

    /** Spring-like squash and overshoot bounce. */
    private fun bouncePill() {
        val pill = pillView ?: return
        pill.animate().cancel()
        pill.scaleX = 1f
        pill.scaleY = 1f
        pill.animate()
            .scaleX(0.78f)
            .scaleY(0.78f)
            .setDuration(90L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                pill.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(560L)
                    .setInterpolator(OvershootInterpolator(4.5f))
                    .start()
            }
            .start()
    }

    // ------------------------------------------------------------------ session

    private fun onPillTapped() {
        if (phase != Phase.IDLE) {
            // Tapping the pill during a session cancels it.
            endSession()
            return
        }

        SoundFx.play(SoundFx.Kind.ACTIVATE)
        bouncePill()
        stopWake(false)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showError("Microphone permission is missing. Open Ghost AI and enable it.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError("Speech recognition is not available on this device.")
            return
        }
        if (!GhostPrefs.hasApiKey(this)) {
            showError("No Gemini API key saved. Open Ghost AI and save your key.")
            return
        }

        phase = Phase.LISTENING
        setPillState(getString(R.string.pill_listening))
        showPopup(
            title = getString(R.string.popup_listening),
            text = "",
            footer = getString(R.string.popup_tap_cancel),
            listening = true,
            isError = false
        )
        // Wait for the activation sound to finish so the mic does not record it.
        mainHandler.removeCallbacks(startListeningRunnable)
        mainHandler.postDelayed(startListeningRunnable, SoundFx.durationMs(SoundFx.Kind.ACTIVATE) + 80L)
    }

    private fun endSession() {
        mainHandler.removeCallbacks(startListeningRunnable)
        mainHandler.removeCallbacks(closeRunnable)
        agentJob?.cancel()
        agentJob = null
        destroyRecognizer()
        tts?.stop()
        hidePopup()
        phase = Phase.IDLE
        setPillState(null)
        // Back to hands-free listening (also un-mutes the beep streams if wake mode is off).
        stopWake(true)
        if (!destroyed) scheduleWake(900L)
    }

    private fun scheduleClose(delayMs: Long) {
        mainHandler.removeCallbacks(closeRunnable)
        mainHandler.postDelayed(closeRunnable, delayMs)
    }

    // ------------------------------------------------------------------ speech recognition

    private fun beginListening() {
        if (phase != Phase.LISTENING) return
        destroyRecognizer()

        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            showError("Could not start speech recognition.")
            return
        }
        sr.setRecognitionListener(listener)
        recognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            SPEECH_LANGUAGE?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            showError("Could not start listening.")
        }
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            try {
                it.setRecognitionListener(null)
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (phase != Phase.LISTENING) return
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            popupView?.findViewById<WaveformView>(R.id.waveform)?.setLevel(level)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (phase != Phase.LISTENING) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                popupView?.findViewById<TextView>(R.id.tvTranscript)?.text = text
            }
        }

        override fun onResults(results: Bundle?) {
            if (phase != Phase.LISTENING) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (text.isEmpty()) {
                fail("I did not catch that. Tap GHOST and try again.")
                return
            }
            popupView?.findViewById<TextView>(R.id.tvTranscript)?.text = text
            destroyRecognizer()
            runAgent(text)
        }

        override fun onError(error: Int) {
            if (phase != Phase.LISTENING) return
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "I did not hear anything. Tap GHOST and try again."

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Microphone permission is missing. Open Ghost AI and enable it."

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Speech recognition needs internet. Check your connection."

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "Speech recognizer is busy. Try again in a moment."

                else -> "Speech recognition failed (code $error). Try again."
            }
            fail(message)
        }
    }

    // ------------------------------------------------------------------ wake word

    private fun wakeAllowed(): Boolean =
        !destroyed && phase == Phase.IDLE && pillView != null && GhostPrefs.isWakeEnabled(this)

    private fun scheduleWake(delayMs: Long) {
        mainHandler.removeCallbacks(startWakeRunnable)
        if (destroyed || !GhostPrefs.isWakeEnabled(this)) return
        mainHandler.postDelayed(startWakeRunnable, delayMs)
    }

    private fun startWakeListening() {
        if (!wakeAllowed()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        stopWake(false)
        val sr: SpeechRecognizer? = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            null
        }
        if (sr == null) {
            scheduleWake(5_000L)
            return
        }
        sr.setRecognitionListener(wakeListener)
        wakeRecognizer = sr
        muteBeep(true)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, wakePreferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            SPEECH_LANGUAGE?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            stopWake(true)
            scheduleWake(3_000L)
        }
    }

    private fun stopWake(unmute: Boolean) {
        mainHandler.removeCallbacks(startWakeRunnable)
        wakeRecognizer?.let {
            try {
                it.setRecognitionListener(null)
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        wakeRecognizer = null
        wakeTriggered = false
        if (unmute) muteBeep(false)
    }

    private fun restartWake(delayMs: Long) {
        stopWake(false)
        scheduleWake(delayMs)
    }

    private fun onWakePrefChanged() {
        updateNotification()
        if (GhostPrefs.isWakeEnabled(this)) {
            wakeFailures = 0
            scheduleWake(200L)
        } else {
            val hadWakeSession = wakeTriggered
            stopWake(true)
            if (hadWakeSession) endSession()
        }
    }

    private fun wakeActive(): Boolean = phase == Phase.IDLE || wakeTriggered

    private val wakeListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (!wakeTriggered) return
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            popupView?.findViewById<WaveformView>(R.id.waveform)?.setLevel(level)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!wakeActive()) return
            handleWakeText(
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
                false
            )
        }

        override fun onResults(results: Bundle?) {
            if (!wakeActive()) return
            wakeFailures = 0
            handleWakeText(
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
                true
            )
        }

        override fun onError(error: Int) {
            if (!wakeActive()) return
            if (wakeTriggered) resetWakeUi()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Normal silence: just listen again.
                    wakeFailures = 0
                    restartWake(150L)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopWake(true)

                else -> {
                    // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE (API 31+):
                    // no offline pack for this language, so allow the online recognizer.
                    if (error == 12 || error == 13) wakePreferOffline = false
                    wakeFailures++
                    restartWake(min(8_000L, 600L * wakeFailures))
                }
            }
        }
    }

    private fun handleWakeText(list: List<String>?, isFinal: Boolean) {
        var remainder: String? = null
        if (list != null) {
            for (candidate in list) {
                val found = WakeWord.match(candidate)
                if (found != null) {
                    remainder = found
                    break
                }
            }
        }

        if (remainder == null) {
            if (isFinal) {
                if (wakeTriggered) resetWakeUi()
                restartWake(100L)
            }
            return
        }

        if (!wakeTriggered) {
            wakeTriggered = true
            phase = Phase.LISTENING
            bouncePill()
            setPillState(getString(R.string.pill_listening))
            showPopup(
                title = getString(R.string.popup_listening),
                text = remainder,
                footer = getString(R.string.popup_tap_cancel),
                listening = true,
                isError = false
            )
        } else {
            popupView?.findViewById<TextView>(R.id.tvTranscript)?.text = remainder
        }
        if (isFinal) onWakeFinal(remainder)
    }

    private fun resetWakeUi() {
        wakeTriggered = false
        if (phase == Phase.LISTENING) phase = Phase.IDLE
        hidePopup()
        setPillState(null)
    }

    private fun onWakeFinal(remainder: String) {
        // The wake recognizer is finished; keep the beep muted for the command step.
        stopWake(false)
        phase = Phase.LISTENING

        if (remainder.length >= 4) {
            // "Jarvis open WhatsApp": the command came in the same breath.
            runAgent(remainder)
        } else {
            // Only the wake word was heard: now listen for the command.
            popupView?.findViewById<TextView>(R.id.tvTranscript)?.text = ""
            SoundFx.play(SoundFx.Kind.ACTIVATE)
            mainHandler.removeCallbacks(startListeningRunnable)
            mainHandler.postDelayed(
                startListeningRunnable,
                SoundFx.durationMs(SoundFx.Kind.ACTIVATE) + 80L
            )
        }
    }

    /**
     * The speech engine plays a start/stop beep each time it listens. While wake mode is on that
     * would beep every few seconds, so the notification and system streams are muted (only if
     * they were not already muted) and restored as soon as wake mode stops.
     */
    private fun muteBeep(mute: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (stream in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (mute) {
                    if (stream !in mutedStreams && !am.isStreamMute(stream)) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                        mutedStreams.add(stream)
                    }
                } else if (stream in mutedStreams) {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                    mutedStreams.remove(stream)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ agent loop

    private fun runAgent(command: String) {
        phase = Phase.THINKING
        setPillState(getString(R.string.pill_thinking))
        showPopup(
            title = getString(R.string.popup_thinking),
            text = command,
            footer = getString(R.string.popup_tap_cancel),
            listening = false,
            isError = false
        )

        agentJob?.cancel()
        agentJob = scope.launch {
            try {
                val apiKey = GhostPrefs.getApiKey(this@GhostOverlayService)
                val steps = ArrayList<String>()
                var lastSignature = ""
                var repeats = 0

                for (step in 1..MAX_STEPS) {
                    val acc = GhostAccessibilityService.instance
                    val screen = if (acc != null) {
                        withContext(Dispatchers.Default) { acc.readScreen() }
                    } else {
                        "ACCESSIBILITY SERVICE IS DISABLED: the screen cannot be read or controlled. " +
                            "Only open_app and speak_reply are possible."
                    }

                    when (val result = gemini.nextAction(apiKey, command, steps, screen)) {
                        is GeminiResult.Failure -> {
                            fail(result.message)
                            return@launch
                        }

                        is GeminiResult.Success -> {
                            val action = result.action

                            if (action.type == GhostActionType.SPEAK_REPLY) {
                                reply(action.reply.ifBlank { "Done." })
                                return@launch
                            }

                            // Same action over and over means we are stuck.
                            val signature = action.signature()
                            if (signature == lastSignature) repeats++ else repeats = 1
                            lastSignature = signature
                            if (repeats >= 3) {
                                reply("I got stuck repeating ${action.describe()}. Please try again or be more specific.")
                                return@launch
                            }

                            // Free the middle of the screen so gestures reach the real app.
                            if (phase != Phase.ACTING) {
                                phase = Phase.ACTING
                                hidePopup()
                            }
                            setPillState(getString(R.string.pill_acting))

                            val outcome = perform(action, acc)
                            steps.add(
                                action.describe() + if (outcome.ok) " -> OK: ${outcome.detail}"
                                else " -> FAILED: ${outcome.detail}"
                            )
                            setPillState(getString(R.string.pill_thinking))
                            delay(settleTimeFor(action.type))
                        }
                    }
                }
                reply("I could not finish this within the step limit. Please try a simpler command.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun perform(action: GhostAction, acc: GhostAccessibilityService?): ActionOutcome {
        if (action.type == GhostActionType.OPEN_APP) {
            val match = AppResolver.find(this, action.target.ifBlank { action.text })
                ?: return ActionOutcome(false, "No installed app matches \"${action.target}\"")
            return if (AppResolver.launch(this, match)) {
                ActionOutcome(true, "Opened ${match.label}")
            } else {
                ActionOutcome(false, "Could not open ${match.label}")
            }
        }

        if (acc == null) {
            return ActionOutcome(false, "Accessibility service is not enabled")
        }
        return when (action.type) {
            GhostActionType.TAP_TEXT -> acc.clickByText(action.target.ifBlank { action.text })
            GhostActionType.INPUT_TEXT -> acc.inputText(action.text, action.target)
            GhostActionType.PRESS_ENTER -> acc.pressEnter()
            GhostActionType.SCROLL -> acc.scroll(action.direction)
            GhostActionType.GO_BACK -> acc.goBack()
            GhostActionType.GO_HOME -> acc.goHome()
            else -> ActionOutcome(false, "Unknown action ${action.type}")
        }
    }

    private fun settleTimeFor(type: String): Long = when (type) {
        GhostActionType.OPEN_APP -> 1800L
        GhostActionType.TAP_TEXT -> 1100L
        GhostActionType.INPUT_TEXT -> 600L
        GhostActionType.PRESS_ENTER -> 1300L
        GhostActionType.SCROLL -> 800L
        else -> 900L
    }

    // ------------------------------------------------------------------ results

    private fun reply(text: String) {
        phase = Phase.REPLY
        setPillState(null)
        SoundFx.play(SoundFx.Kind.DONE)
        showPopup(
            title = getString(R.string.popup_reply),
            text = text,
            footer = getString(R.string.popup_tap_close),
            listening = false,
            isError = false
        )
        // Safety timer in case speech never reports completion.
        scheduleClose(min(20_000L, max(6_000L, text.length * 90L)))
        speak(text)
    }

    private fun fail(message: String) {
        phase = Phase.REPLY
        destroyRecognizer()
        setPillState(null)
        SoundFx.play(SoundFx.Kind.ERROR)
        showPopup(
            title = getString(R.string.popup_error),
            text = message,
            footer = getString(R.string.popup_tap_close),
            listening = false,
            isError = true
        )
        scheduleClose(6_000L)
    }

    /** Error shown before a session really starts (missing permission or key). */
    private fun showError(message: String) {
        fail(message)
    }

    // ------------------------------------------------------------------ text to speech

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { if (phase == Phase.REPLY) scheduleClose(1_200L) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { if (phase == Phase.REPLY) scheduleClose(1_200L) }
                    }
                })
            }
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) return
        val hasDevanagari = text.any { it in 'ऀ'..'ॿ' }
        val wanted = if (hasDevanagari) Locale("hi", "IN") else Locale("en", "IN")
        val result = engine.setLanguage(wanted)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ghost_reply")
    }

    // ------------------------------------------------------------------ popup

    private fun showPopup(title: String, text: String, footer: String, listening: Boolean, isError: Boolean) {
        val existing = popupView
        if (existing != null) {
            bindPopup(existing, title, text, footer, listening, isError)
            return
        }

        val view = themedInflater.inflate(R.layout.overlay_popup, null)
        val dm = resources.displayMetrics
        val width = min((dm.widthPixels * 0.9f).toInt(), dp(440))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        view.setOnClickListener { if (phase != Phase.IDLE) endSession() }
        bindPopup(view, title, text, footer, listening, isError)

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            return
        }
        popupView = view
        popupParams = params
        animatePopupIn(view)
    }

    private fun bindPopup(
        view: View,
        title: String,
        text: String,
        footer: String,
        listening: Boolean,
        isError: Boolean
    ) {
        val titleView = view.findViewById<TextView>(R.id.tvPopupTitle)
        val transcript = view.findViewById<TextView>(R.id.tvTranscript)
        val footerView = view.findViewById<TextView>(R.id.tvPopupFooter)
        val waveform = view.findViewById<View>(R.id.waveform)

        titleView.text = title
        titleView.setTextColor(getColor(if (isError) R.color.ghost_danger else R.color.ghost_cyan))
        transcript.text = text
        transcript.setTextColor(getColor(if (isError) R.color.ghost_danger else R.color.ghost_text))
        footerView.text = footer
        waveform.visibility = if (listening) View.VISIBLE else View.GONE
    }

    private fun animatePopupIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(380L)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()

        // Expanding pulse rings behind the mic orb.
        for ((id, startDelay) in listOf(R.id.pulseRing1 to 0L, R.id.pulseRing2 to 700L)) {
            val ring = view.findViewById<View>(id) ?: continue
            val animator = ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.55f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.55f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 0f)
            ).apply {
                duration = 1400L
                this.startDelay = startDelay
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
            popupAnimators.add(animator)
        }

        // Breathing orb.
        view.findViewById<View>(R.id.orb)?.let { orb ->
            val animator = ObjectAnimator.ofPropertyValuesHolder(
                orb,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.09f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.09f)
            ).apply {
                duration = 700L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
            popupAnimators.add(animator)
        }

        // Glowing halo.
        view.findViewById<View>(R.id.popupGlow)?.let { glow ->
            val animator = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.45f, 1f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
            popupAnimators.add(animator)
        }
    }

    private fun hidePopup() {
        popupAnimators.forEach { it.cancel() }
        popupAnimators.clear()
        removeView(popupView)
        popupView = null
        popupParams = null
    }

    // ------------------------------------------------------------------ helpers

    private fun removeView(view: View?) {
        if (view == null) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
        }
    }

    private fun updateLayoutSafely(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ACTION_STOP = "com.ghost.ai.action.STOP"
        private const val CHANNEL_ID = "ghost_overlay"
        private const val NOTIFICATION_ID = 4107
        private const val MAX_STEPS = 12

        /** BCP-47 tag such as "hi-IN" or "en-IN". null = use the device language. */
        private val SPEECH_LANGUAGE: String? = null

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GhostOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GhostOverlayService::class.java))
        }
    }
}
