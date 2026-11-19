package com.example.watchtranslator

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

private const val TAG = "VozTest"

/**
 * Prueba SOLO de reconocimiento de voz (voz a texto).
 * No traduce, no habla y no usa ninguna otra clase de la app.
 */
class SpeechTestActivity : ComponentActivity() {

    private val langs = listOf(
        "es-MX" to "Español (MX)",
        "es-ES" to "Español (ES)",
        "en-US" to "English (US)",
        "fr-FR" to "Français",
        "de-DE" to "Deutsch",
    )

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var retries = 0

    private var listening by mutableStateOf(false)
    private var state by mutableStateOf("Toca el micrófono")
    private var level by mutableStateOf(0f)
    private var partial by mutableStateOf("")
    private var errorText by mutableStateOf("")
    private var langIndex by mutableStateOf(0)
    private var preferOffline by mutableStateOf(false)
    private var onDevice by mutableStateOf(false)
    private var reconnects by mutableStateOf(0)
    private val history = mutableStateListOf<String>()

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    // El mismo reconocedor se reutiliza entre frases (menos desconexiones)
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { state = "Escuchando… habla ahora" }
        override fun onBeginningOfSpeech() { state = "Voz detectada"; retries = 0 }
        override fun onRmsChanged(rms: Float) { level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() { state = "Procesando…"; level = 0f }
        override fun onEvent(t: Int, p: Bundle?) {}

        override fun onPartialResults(p: Bundle?) {
            p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.let { partial = it; Log.d(TAG, "Parcial: $it") }
        }

        override fun onResults(r: Bundle?) {
            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            Log.d(TAG, "Final: \"$text\"")
            partial = ""
            retries = 0
            if (text.isNotBlank()) history.add(0, text)
            errorText = ""
            main.postDelayed({ listenOnce() }, 300)
        }

        override fun onError(error: Int) {
            Log.d(TAG, "Error $error: ${errorName(error)}")
            partial = ""
            level = 0f
            when (error) {
                // Silencio o no entendió: normal, vuelve a escuchar
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> {
                    state = "No se oyó nada claro, reintentando…"
                    main.postDelayed({ listenOnce() }, 300)
                }
                // Servicio desconectado / ocupado / cliente: recrear y reintentar
                11, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    if (retries < 5) {
                        retries++
                        reconnects++
                        state = "Servicio de voz desconectado, reconectando ($retries/5)…"
                        recognizer?.destroy()
                        recognizer = null
                        main.postDelayed({ listenOnce() }, 1000)
                    } else {
                        stopAll()
                        errorText = "Error $error: ${errorName(error)} (no se pudo reconectar)"
                    }
                }
                else -> {
                    stopAll()
                    errorText = "Error $error: ${errorName(error)}"
                    state = "Se detuvo por error"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val available = SpeechRecognizer.isRecognitionAvailable(this)
        val onDeviceOk = onDeviceAvailable()
        Log.d(TAG, "Reconocimiento disponible: $available | en dispositivo: $onDeviceOk | API ${Build.VERSION.SDK_INT}")

        setContent {
            val permission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> Log.d(TAG, "Permiso micrófono: $granted") }
            LaunchedEffect(Unit) { permission.launch(Manifest.permission.RECORD_AUDIO) }

            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        if (available) "Servicio de voz: SÍ ✓" else "Servicio de voz: NO ✗",
                        fontSize = 11.sp,
                        color = if (available) Color(0xFF7CDB8A) else Color(0xFFFF8A80)
                    )
                    Text(
                        if (onDeviceOk) "En dispositivo (offline): SÍ ✓" else "En dispositivo (offline): NO ✗",
                        fontSize = 11.sp,
                        color = if (onDeviceOk) Color(0xFF7CDB8A) else Color(0xFFFF8A80)
                    )
                    Chip(
                        onClick = { if (!listening) langIndex = (langIndex + 1) % langs.size },
                        label = { Text(langs[langIndex].second, fontSize = 12.sp) },
                        secondaryLabel = { Text(langs[langIndex].first, fontSize = 10.sp) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Chip(
                        onClick = { if (!listening) preferOffline = !preferOffline },
                        label = { Text("Preferir offline", fontSize = 12.sp) },
                        secondaryLabel = { Text(if (preferOffline) "sí" else "no", fontSize = 10.sp) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Chip(
                        onClick = {
                            if (!listening) {
                                if (onDevice) onDevice = false
                                else if (onDeviceOk) onDevice = true
                                else errorText = "Este reloj no ofrece reconocimiento en dispositivo"
                            }
                        },
                        label = { Text("Solo en dispositivo", fontSize = 12.sp) },
                        secondaryLabel = {
                            Text(if (onDevice) "sí" else if (onDeviceOk) "no" else "no disponible", fontSize = 10.sp)
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { if (listening) stopAll() else startAll() },
                        modifier = Modifier.size(56.dp)
                    ) { Text(if (listening) "■" else "🎤", fontSize = 22.sp) }

                    // Barra de volumen: si se mueve, el micrófono SÍ recibe audio
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF3C3C3C))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(level)
                                .fillMaxHeight()
                                .background(Color(0xFF7CDB8A))
                        )
                    }

                    Text(state, fontSize = 11.sp, textAlign = TextAlign.Center)
                    if (reconnects > 0) {
                        Text("Reconexiones: $reconnects", fontSize = 10.sp, textAlign = TextAlign.Center)
                    }
                    if (partial.isNotBlank()) {
                        Text("… $partial", fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    if (errorText.isNotBlank()) {
                        Text(errorText, fontSize = 11.sp, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
                    }
                    history.forEach {
                        Text(it, fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colors.primary)
                    }
                }
            }
        }
    }

    private fun startAll() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            errorText = "Este reloj no tiene servicio de reconocimiento de voz"
            return
        }
        listening = true
        errorText = ""
        retries = 0
        listenOnce()
    }

    private fun stopAll() {
        listening = false
        state = "Detenido"
        level = 0f
        partial = ""
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val r = if (onDevice) {
            if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else null
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        r?.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    private fun listenOnce() {
        if (!listening) return
        val r = ensureRecognizer()
        if (r == null) {
            stopAll()
            errorText = "No se pudo crear el reconocedor (modo en dispositivo no disponible)"
            return
        }
        state = "Preparando micrófono…"
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langs[langIndex].first)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline || onDevice)
            }
        )
    }

    private fun errorName(code: Int) = when (code) {
        1 -> "tiempo de red agotado"
        2 -> "error de red (¿sin internet?)"
        3 -> "error de audio (micrófono)"
        4 -> "error del servidor de voz"
        5 -> "error del cliente"
        6 -> "no se oyó voz (silencio)"
        7 -> "no se entendió lo dicho"
        8 -> "reconocedor ocupado"
        9 -> "falta permiso de micrófono"
        10 -> "demasiadas solicitudes"
        11 -> "servicio de voz desconectado"
        12 -> "idioma no soportado"
        13 -> "idioma no disponible (falta paquete)"
        else -> "desconocido"
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        super.onDestroy()
    }
}