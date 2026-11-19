package com.example.watchtranslator

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Modelos de voz OFFLINE (Vosk) para el idioma de entrada.
 * Se descargan una sola vez, con internet, y se guardan en la memoria de la app.
 */
object VoskModels {

    // Idioma (etiqueta de Lang.tag) -> nombre del modelo pequeño en alphacephei.com/vosk/models
    private val FILES = mapOf(
        "es-MX" to "vosk-model-small-es-0.42",
        "en-US" to "vosk-model-small-en-us-0.15",
        "fr-FR" to "vosk-model-small-fr-0.22",
        "de-DE" to "vosk-model-small-de-0.15",
        "pt-BR" to "vosk-model-small-pt-0.3",
        "it-IT" to "vosk-model-small-it-0.22",
        "ja-JP" to "vosk-model-small-ja-0.22",
        "zh-CN" to "vosk-model-small-cn-0.22",
        "ko-KR" to "vosk-model-small-ko-0.22",
    )

    fun supports(tag: String) = tag in FILES

    private fun root(ctx: Context) = File(ctx.filesDir, "vosk").apply { mkdirs() }

    /** Carpeta del modelo ya descomprimido (la que contiene "am", "conf", "graph"...). */
    fun modelDir(ctx: Context, tag: String): File? {
        val name = FILES[tag] ?: return null
        val base = File(root(ctx), name)
        if (!base.isDirectory) return null
        if (File(base, "am").isDirectory) return base
        return base.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").isDirectory }
    }

    fun isReady(ctx: Context, tag: String): Boolean {
        val name = FILES[tag] ?: return false
        return File(root(ctx), "$name.done").exists() && modelDir(ctx, tag) != null
    }

    /**
     * Descarga y descomprime el modelo en un hilo aparte.
     * Los callbacks se llaman en ese hilo: quien los reciba debe pasar al hilo principal.
     */
    fun download(ctx: Context, tag: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val name = FILES[tag] ?: return onError("idioma sin modelo offline")
        Thread {
            val root = root(ctx)
            try {
                File(root, name).deleteRecursively()
                File(root, "$name.done").delete()

                val conn = URL("https://alphacephei.com/vosk/models/$name.zip")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")

                val base = root.canonicalPath
                conn.inputStream.buffered().use { raw ->
                    ZipInputStream(raw).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val out = File(root, entry.name)
                            // Protección: no escribir fuera de la carpeta de la app
                            if (!out.canonicalPath.startsWith(base + File.separator)) {
                                throw IOException("archivo inseguro en el zip")
                            }
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { zip.copyTo(it) }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                if (modelDir(ctx, tag) == null) throw IOException("el zip no tenía el formato esperado")
                File(root, "$name.done").writeText("ok")
                onDone()
            } catch (e: Exception) {
                File(root, name).deleteRecursively()
                onError(e.message ?: "error desconocido")
            }
        }.start()
    }
}