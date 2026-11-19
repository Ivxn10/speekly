package com.example.watchtranslator

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

private const val TAG = "Translator"

// false = una frase por toque (escucha, cierra el micro, traduce y habla).
// true  = al terminar de hablar vuelve a escuchar solo (modo conversación).
private const val AUTO_REPEAT = false

enum class Side { INPUT, OUTPUT }

enum class Phase { IDLE, PREPARING, LISTENING, TRANSLATING, SPEAKING }

class TranslatorViewModel(private val app: Application) : AndroidViewModel(app) {

    var from by mutableStateOf(LANGS[0]); private set
    var to by mutableStateOf(LANGS[1]); private set
    var phase by mutableStateOf(Phase.IDLE); private set
    var heard by mutableStateOf(""); private set
    var translated by mutableStateOf(""); private set
    var status by mutableStateOf("Toca para hablar"); private set
    var level by mutableStateOf(0f); private set

    // Dirección del último deslizamiento (+1 arriba, -1 abajo), para la animación
    var inputDir by mutableStateOf(1); private set
    var outputDir by mutableStateOf(1); private set

    var downloaded by mutableStateOf<Set<String>>(emptySet()); private set
    var downloading by mutableStateOf<Set<String>>(emptySet()); private set

    var toast by mutableStateOf<String?>(null); private set
    private var toastToken = 0

    private val main = Handler(Looper.getMainLooper())
    private val modelManager = RemoteModelManager.getInstance()
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var translatorKey = ""
    private var session = 0
    private var retries = 0
    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(app) {
        ttsReady = it == TextToSpeech.SUCCESS
        Log.d(TAG, "TextToSpeech listo: $ttsReady")
    }

    // Se ejecuta 1.2 s después del último cambio de idioma (evita descargar
    // todos los idiomas por los que pasas al deslizar)
    private val checkModels = Runnable {
        refreshModels()
        ensureModel(from)
        ensureModel(to)
    }

    init {
        refreshModels()
        ensureModel(from)
        ensureModel(to)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { main.post { finishCycle() } }
            override fun onDone(id: String?) { main.post { finishCycle() } }
        })
    }

    // ---------- Avisos ----------
    fun showToast(msg: String, ms: Long = 3000) {
        Log.d(TAG, "Aviso: $msg")
        toast = msg
        toastToken++
        val mine = toastToken
        main.postDelayed({ if (mine == toastToken) toast = null }, ms)
    }

    // ---------- Modelos de traducción ----------
    fun refreshModels() {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                downloaded = models.map { it.language }.toSet()
                Log.d(TAG, "Modelos descargados: $downloaded")
            }
            .addOnFailureListener { Log.d(TAG, "No pude leer modelos: ${it.message}") }
    }

    private fun ensureModel(lang: Lang) {
        if (lang.ml in downloading) return
        val model = TranslateRemoteModel.Builder(lang.ml).build()
        modelManager.isModelDownloaded(model).addOnSuccessListener { has ->
            if (has) {
                refreshModels()
                return@addOnSuccessListener
            }
            downloading = downloading + lang.ml
            showToast("Descargando ${lang.name}…", 5000)
            modelManager.download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    downloading = downloading - lang.ml
                    refreshModels()
                    showToast("✓ ${lang.name} listo (ya funciona sin internet)", 4000)
                }
                .addOnFailureListener {
                    downloading = downloading - lang.ml
                    Log.d(TAG, "Falló descarga ${lang.name}: ${it.message}")
                    showToast("✗ ${lang.name}: no se pudo descargar. Revisa internet", 5000)
                }
        }
    }

    // ---------- Cambiar idioma deslizando ----------
    // step = +1 (siguiente) o -1 (anterior). Nunca deja el mismo idioma en ambos lados.
    fun swipe(side: Side, step: Int) {
        if (phase != Phase.IDLE) {
            showToast("Detén la traducción para cambiar de idioma")
            return
        }
        val current = if (side == Side.INPUT) from else to
        val other = if (side == Side.INPUT) to else from
        if (side == Side.INPUT) inputDir = step else outputDir = step
        var idx = LANGS.indexOf(current)
        do {
            idx = (idx + step + LANGS.size) % LANGS.size
        } while (LANGS[idx] == other)
        if (side == Side.INPUT) from = LANGS[idx] else to = LANGS[idx]
        heard = ""
        translated = ""
        main.removeCallbacks(checkModels)
        main.postDelayed(checkModels, 1200)
    }

    // ---------- Flujo: escuchar → cerrar micro → traducir → hablar ----------
    fun onMicTap() = if (phase == Phase.IDLE) start() else cancel()

    private fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            showToast("Este reloj no tiene reconocimiento de voz", 5000)
            return
        }
        session++
        retries = 0
        heard = ""
        translated = ""
        phase = Phase.PREPARING
        status = "Preparando…"

        val key = "${from.ml}>${to.ml}"
        if (key != translatorKey) {
            translator?.close()
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(from.ml)
                    .setTargetLanguage(to.ml)
                    .build()
            )
            translatorKey = key
        }
        if (from.ml !in downloaded || to.ml !in downloaded) {
            status = "Descargando modelos…"
            showToast("Descargando modelos (necesita internet)", 5000)
        }
        val s = session
        translator!!.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                refreshModels()
                if (s == session && phase == Phase.PREPARING) listen()
            }
            .addOnFailureListener {
                if (s == session) fail("✗ Falta el modelo. Conecta a internet")
            }
    }

    fun cancel() {
        session++
        phase = Phase.IDLE
        status = "Toca para hablar"
        level = 0f
        tts.stop()
        closeMic()
    }

    private fun fail(msg: String) {
        phase = Phase.IDLE
        status = "Toca para hablar"
        level = 0f
        closeMic()
        showToast(msg, 4000)
    }

    private fun closeMic() {
        recognizer?.destroy()
        recognizer = null
        level = 0f
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { status = "Habla ahora…" }
        override fun onBeginningOfSpeech() { retries = 0 }
        override fun onRmsChanged(rms: Float) { level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() { status = "Cerrando micrófono…"; level = 0f }
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onPartialResults(p: Bundle?) {}   // no se muestra nada mientras hablas

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            Log.d(TAG, "Escuché: \"$text\"")
            val s = session
            main.post {
                if (s != session || phase != Phase.LISTENING) return@post
                closeMic() // el micrófono se cierra ANTES de traducir
                if (text.isBlank()) {
                    fail("No te escuché. Intenta de nuevo")
                } else {
                    heard = text
                    status = "Traduciendo…"
                    translate(text)
                }
            }
        }

        override fun onError(error: Int) {
            Log.d(TAG, "Error de reconocimiento: $error")
            if (phase != Phase.LISTENING) return
            val s = session
            when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH ->
                    main.post { if (s == session) fail("No te escuché. Intenta de nuevo") }

                // 11 = servicio desconectado; también se reintenta si está ocupado o falla el cliente
                11, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    if (retries < 3) {
                        retries++
                        status = "Reconectando voz ($retries/3)…"
                        main.post { closeMic() }
                        main.postDelayed({
                            if (s == session && phase == Phase.LISTENING) listen()
                        }, 1000)
                    } else {
                        main.post { if (s == session) fail("El servicio de voz no responde") }
                    }
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    main.post { if (s == session) fail("Activa el permiso de micrófono en Ajustes") }

                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    main.post { if (s == session) fail("Sin internet: la voz necesita conexión (error $error)") }

                12, 13 ->
                    main.post { if (s == session) fail("Falta el idioma de voz ${from.name} (error $error)") }

                else -> main.post { if (s == session) fail("Error de voz ($error)") }
            }
        }
    }

    private fun listen() {
        phase = Phase.LISTENING
        status = "Habla ahora…"
        closeMic()
        Log.d(TAG, "Escuchando en ${from.tag}")
        recognizer = SpeechRecognizer.createSpeechRecognizer(app).also { r ->
            r.setRecognitionListener(listener)
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, from.tag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
            )
        }
    }

    private fun translate(text: String) {
        val t = translator ?: return fail("No se pudo traducir")
        phase = Phase.TRANSLATING
        val s = session
        t.translate(text)
            .addOnSuccessListener { result ->
                Log.d(TAG, "Traducción: \"$result\"")
                if (s == session && phase == Phase.TRANSLATING) {
                    translated = result
                    speak(result)
                }
            }
            .addOnFailureListener {
                Log.d(TAG, "Falló traducción: ${it.message}")
                if (s == session) fail("✗ No se pudo traducir")
            }
    }

    private fun speak(text: String) {
        if (!ttsReady) return fail("La voz de salida aún no está lista")
        val r = tts.setLanguage(Locale.forLanguageTag(to.tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            return fail("Falta la voz de ${to.name} en el reloj")
        }
        phase = Phase.SPEAKING
        status = "Hablando…"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
    }

    private fun finishCycle() {
        if (phase != Phase.SPEAKING) return
        if (AUTO_REPEAT) {
            start()
        } else {
            phase = Phase.IDLE
            status = "Toca para hablar"
        }
    }

    override fun onCleared() {
        recognizer?.destroy()
        translator?.close()
        tts.shutdown()
    }
}