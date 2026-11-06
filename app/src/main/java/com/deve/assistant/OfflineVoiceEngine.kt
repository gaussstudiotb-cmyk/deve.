package com.deve.assistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Reconnaissance vocale entièrement locale avec Vosk.
 * Le modèle français doit être placé dans app/src/main/assets/model-fr/.
 */
class OfflineVoiceEngine(
    context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var loading = false
    private var loaded = false

    fun initialize(onReady: () -> Unit) {
        if (loaded) {
            onReady()
            return
        }
        if (loading) return
        loading = true
        LibVosk.setLogLevel(LogLevel.WARN)
        StorageService.unpack(
            appContext,
            "model-fr",
            "vosk-model-fr",
            { unpacked ->
                model = unpacked
                loaded = true
                loading = false
                onReady()
            },
            { error ->
                loading = false
                Log.e("DeVE", "Impossible de charger le modèle Vosk", error)
                callbacks.onError(
                    "Modèle vocal français absent. Ajoute le modèle Vosk dans app/src/main/assets/model-fr."
                )
            }
        )
    }

    @Synchronized
    fun start() {
        if (!loaded || model == null || speechService != null) return
        try {
            val rec = Recognizer(model, 16000.0f)
            recognizer = rec
            val service = SpeechService(rec, 16000.0f)
            speechService = service
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = parseText(hypothesis, "partial")
                    if (text.isNotBlank()) callbacks.onPartial(text)
                }

                override fun onResult(hypothesis: String?) {
                    val text = parseText(hypothesis, "text")
                    if (text.isNotBlank()) callbacks.onFinal(text)
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = parseText(hypothesis, "text")
                    if (text.isNotBlank()) callbacks.onFinal(text)
                }

                override fun onError(exception: Exception?) {
                    callbacks.onError(exception?.message ?: "Erreur de reconnaissance vocale locale.")
                }

                override fun onTimeout() {
                    // L'écoute continue en mode permanent ; aucun redémarrage réseau.
                }
            })
        } catch (e: IOException) {
            Log.e("DeVE", "Démarrage Vosk impossible", e)
            callbacks.onError("Impossible d'ouvrir le microphone pour Vosk.")
        } catch (e: Exception) {
            Log.e("DeVE", "Erreur Vosk", e)
            callbacks.onError("Erreur du moteur vocal local : ${e.message ?: "inconnue"}.")
        }
    }

    private fun parseText(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(key, "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    fun stop() {
        val service = speechService
        speechService = null
        try {
            service?.stop()
            service?.shutdown()
        } catch (e: Exception) {
            Log.w("DeVE", "Arrêt SpeechService", e)
        }
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    fun release() {
        stop()
        try {
            model?.close()
        } catch (_: Exception) {
        }
        model = null
        loaded = false
    }
}
