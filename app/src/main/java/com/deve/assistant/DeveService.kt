package com.deve.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Service au premier plan : écoute en continu, attend « DeVE », affiche l'orbe,
 * envoie la demande au cerveau, parle, puis exécute l'action.
 */
class DeveService : Service() {

    private enum class Mode { WAKE, COMMAND, BUSY }

    companion object {
        const val ACTION_TRIGGER = "com.deve.assistant.TRIGGER"
        const val ACTION_STOP = "com.deve.assistant.STOP"
        private const val CHANNEL = "deve"
        private const val TAG = "DeVE"

        @Volatile
        var running = false

        // Mot d'éveil : « DeVE » est souvent transcrit dev / dève / dave.
        private val WAKE = Regex(
            "(?<![\\p{L}])(?:deve|dève|dév|dev|dave)(?![\\p{L}])",
            setOf(RegexOption.IGNORE_CASE, RegexOption.UNICODE_CASE)
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var wm: WindowManager
    private lateinit var brain: Brain
    private lateinit var actions: Actions

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mode = Mode.WAKE
    private var started = false
    private var speaking = false
    private var requestId = 0
    private var pendingAction: (() -> Unit)? = null
    private var deferredSay: String? = null

    private var orb: OrbView? = null

    private var recorder: MediaRecorder? = null
    private var recordingPfd: ParcelFileDescriptor? = null

    // ------------------------------------------------------------ cycle de vie

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        brain = Brain(getSharedPreferences("deve", MODE_PRIVATE))
        actions = Actions(this, this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { finishSpeaking() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { finishSpeaking() }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post { finishSpeaking() }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        main.post { finishSpeaking() }
                    }
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startAsForeground()) return START_NOT_STICKY
        running = true
        if (!started) {
            started = true
            setupRecognizer()
            listen()
        }
        if (intent?.action == ACTION_TRIGGER && mode != Mode.BUSY) enterCommand()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopRecording(false)
        hideOrb()
        recognizer?.destroy()
        tts?.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startAsForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "DeVE", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DeveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DeVE est à l'écoute")
            .setContentText("Dis « DeVE » suivi de ta demande")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Arrêter", stop
                ).build()
            )
            .build()
        return try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground refusé", e)
            toast("DeVE ne peut pas démarrer : autorise le micro puis relance depuis l'appli.")
            stopSelf()
            false
        }
    }

    // ------------------------------------------------------------ écoute

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Reconnaissance vocale indisponible : installe ou active l'appli Google.")
            stopSelf()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
    }

    private val listenRunnable = Runnable { listen() }

    private fun listenLater(ms: Long) {
        main.removeCallbacks(listenRunnable)
        main.postDelayed(listenRunnable, ms)
    }

    private fun listen() {
        main.removeCallbacks(listenRunnable)
        if (mode == Mode.BUSY || !running) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            Log.e(TAG, "startListening", e)
            listenLater(1500)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            orb?.setLevel((rmsdB + 2f) / 12f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (mode != Mode.WAKE) return
            val t = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (afterWake(t) != null) showOrb(OrbView.State.LISTENING)
        }

        override fun onResults(results: Bundle?) {
            handleResults(
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            )
        }

        override fun onError(error: Int) {
            handleError(error)
        }
    }

    /** Renvoie ce qui suit « DeVE » (éventuellement vide), ou null si le mot d'éveil est absent. */
    private fun afterWake(t: String): String? {
        val m = WAKE.find(t) ?: return null
        if (m.range.first > 14) return null
        return t.substring(m.range.last + 1).trim { it.isWhitespace() || it in ",.!?;:-" }
    }

    private fun handleResults(list: List<String>) {
        when (mode) {
            Mode.WAKE -> {
                for (t in list) {
                    val rest = afterWake(t) ?: continue
                    if (rest.length >= 3) process(rest) else enterCommand()
                    return
                }
                hideOrb()
                listenLater(150)
            }
            Mode.COMMAND -> {
                val raw = list.firstOrNull()?.trim().orEmpty()
                val text = (afterWake(raw) ?: raw).trim()
                if (text.isEmpty()) backToWake() else process(text)
            }
            Mode.BUSY -> {}
        }
    }

    private fun handleError(code: Int) {
        if (mode == Mode.BUSY) return
        if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            toast("Autorise le micro dans l'appli DeVE.")
            stopSelf()
            return
        }
        if (mode == Mode.COMMAND) {
            backToWake()
            return
        }
        hideOrb()
        when (code) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                setupRecognizer()
                listenLater(1200)
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> listenLater(3000)
            else -> listenLater(250)
        }
    }

    private fun enterCommand() {
        mode = Mode.COMMAND
        showOrb(OrbView.State.LISTENING)
        recognizer?.cancel()
        listenLater(250)
    }

    private fun backToWake() {
        mode = Mode.WAKE
        hideOrb()
        listenLater(300)
    }

    // ------------------------------------------------------------ traitement

    private fun process(text: String) {
        mode = Mode.BUSY
        recognizer?.cancel()
        showOrb(OrbView.State.THINKING)
        val id = ++requestId
        io.execute {
            val r = brain.ask(text)
            main.post {
                if (id != requestId || !running) return@post
                val call = r.call
                if (call != null) {
                    val plan = actions.plan(call.name, call.args)
                    say(plan.message, plan.run)
                } else {
                    say(r.text ?: "Je n'ai pas compris.")
                }
            }
        }
    }

    private fun say(text: String, after: (() -> Unit)? = null) {
        mode = Mode.BUSY
        pendingAction = after
        speaking = true
        showOrb(OrbView.State.SPEAKING)
        val ok = ttsReady &&
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "deve") == TextToSpeech.SUCCESS
        if (!ok) finishSpeaking()
    }

    private fun finishSpeaking() {
        if (!speaking) return
        speaking = false
        val action = pendingAction
        pendingAction = null
        try {
            action?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Action échouée", e)
        }
        val retry = deferredSay
        if (retry != null) {
            deferredSay = null
            say(retry)
            return
        }
        if (recorder != null) {
            mode = Mode.BUSY
            showOrb(OrbView.State.RECORDING)
            return
        }
        backToWake()
    }

    // ------------------------------------------------------------ enregistrement

    fun startRecording(seconds: Int) {
        val name = "DeVE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/DeVE")
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        val pfd = uri?.let { contentResolver.openFileDescriptor(it, "w") }
        if (uri == null || pfd == null) {
            deferredSay = "Je n'arrive pas à créer le fichier d'enregistrement."
            return
        }
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(pfd.fileDescriptor)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder", e)
            r.release()
            pfd.close()
            contentResolver.delete(uri, null, null)
            deferredSay = "Je n'arrive pas à enregistrer."
            return
        }
        recorder = r
        recordingPfd = pfd
        main.removeCallbacks(stopRecRunnable)
        main.postDelayed(stopRecRunnable, seconds * 1000L)
    }

    private val stopRecRunnable = Runnable { stopRecording(true) }

    private fun stopRecording(announce: Boolean) {
        main.removeCallbacks(stopRecRunnable)
        val r = recorder ?: return
        try {
            r.stop()
        } catch (ignored: Exception) {
        }
        r.release()
        recorder = null
        try {
            recordingPfd?.close()
        } catch (ignored: Exception) {
        }
        recordingPfd = null
        if (announce) say("Enregistrement terminé. Tu le trouveras dans Musique, dossier DeVE.")
    }

    // ------------------------------------------------------------ orbe

    private fun onOrbTap() {
        when {
            recorder != null -> stopRecording(true)
            mode == Mode.BUSY && speaking -> {
                pendingAction = null
                tts?.stop()
            }
            else -> {
                requestId++
                recognizer?.cancel()
                backToWake()
            }
        }
    }

    private fun showOrb(state: OrbView.State) {
        val o = orb ?: createOrb() ?: return
        o.state = state
    }

    private fun createOrb(): OrbView? {
        if (!Settings.canDrawOverlays(this)) return null
        val density = resources.displayMetrics.density
        val size = (230 * density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
        }
        val o = OrbView(this)
        o.setOnClickListener { onOrbTap() }
        return try {
            wm.addView(o, lp)
            orb = o
            o
        } catch (e: Exception) {
            Log.e(TAG, "Orbe impossible", e)
            null
        }
    }

    private fun hideOrb() {
        orb?.let {
            try {
                wm.removeView(it)
            } catch (ignored: Exception) {
            }
        }
        orb = null
    }

    private fun toast(msg: String) =
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
}
