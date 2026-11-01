package com.bpoppa.pocketai

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Self-contained engine: bundles llama-server (arm64) in jniLibs and runs it
 * as a private child process on 127.0.0.1:8181. Downloads its own model on
 * first run straight from HuggingFace into app storage. No Termux needed.
 */
class EmbeddedServerManager(private val ctx: Context) : ServerManager {
    override val port = 8181
    override val flavorName = "embedded"

    /** Stable per-install key: a surviving server instance stays authorised after an app restart. */
    override val apiKey: String = run {
        val p = ctx.getSharedPreferences("pocketai", android.content.Context.MODE_PRIVATE)
        p.getString("llama_api_key", null)
            ?: java.util.UUID.randomUUID().toString().replace("-", "")
                .also { p.edit().putString("llama_api_key", it).apply() }
    }

    private val modelUrl =
        "https://huggingface.co/bartowski/mlabonne_gemma-3-4b-it-abliterated-GGUF/resolve/main/mlabonne_gemma-3-4b-it-abliterated-Q4_K_M.gguf"
    private val modelFile: File
        get() = File(ctx.filesDir, "model.gguf")

    private var proc: Process? = null
    private var gateway: AnswerGateway? = null

    private fun ensureGateway() {
        if (gateway != null) return
        gateway = AnswerGateway(8182, LlamaClient(port, apiKey), onWake = { ensureStarted({}) }, apiToken = apiKey)
        gateway?.start()
        android.util.Log.d("PocketAI", "answer gateway on 127.0.0.1:8182 (token $apiKey)")
    }

    override fun ensureStarted(onLog: (String) -> Unit) {
        if (proc != null && proc!!.isAlive) {
            ensureGateway()
            return
        }
        if (!modelFile.exists() || modelFile.length() < 1_000_000_000L) {
            onLog("no model yet — downloading (~2.5 GB, Wi-Fi only run)…")
            downloadModel(onLog)
        }
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libllama_server.so")
        if (!bin.exists()) {
            onLog("bundled llama-server missing")
            return
        }
        killStaleServers()
        onLog("launching bundled llama-server…")
        proc = ProcessBuilder(
            bin.absolutePath,
            "-m", modelFile.absolutePath,
            "--host", "127.0.0.1", "--port", port.toString(),
            "-c", "4096", "-t", "4", "--no-webui",
            "--api-key", apiKey
        )
            .redirectErrorStream(true)
            .start()
        // Drain output so the child never blocks on a full pipe.
        Thread {
            try {
                proc?.inputStream?.bufferedReader()?.forEachLine { }
            } catch (_: Exception) {}
        }.start()
        ensureGateway()
    }

    private fun downloadModel(onLog: (String) -> Unit) {
        val tmp = File(ctx.filesDir, "model.gguf.part")
        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(modelUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download failed: HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            var done = 0L
            tmp.outputStream().use { out ->
                resp.body?.byteStream()?.use { ins ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done % (64L * 1024 * 1024) < (1 shl 16)) {
                            onLog("downloading model: ${done / 1048576} / ${total / 1048576} MB")
                        }
                    }
                }
            }
        }
        if (!tmp.renameTo(modelFile)) {
            tmp.copyTo(modelFile, overwrite = true)
            tmp.delete()
        }
        onLog("model downloaded (${modelFile.length() / 1048576} MB)")
    }

    private fun killStaleServers() {
        try {
            val procs = File("/proc").listFiles { f -> f.name.toIntOrNull() != null } ?: return
            procs.forEach { p ->
                val cmd = try {
                    File(p, "cmdline").readBytes().toString(Charsets.ISO_8859_1)
                } catch (_: Exception) { "" }
                if (cmd.contains("libllama_server.so")) {
                    ProcessBuilder("kill", p.name).start()
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        proc?.destroy()
        proc = null
        gateway?.stop()
        gateway = null
    }

    override fun isModelReady() = modelFile.exists() && modelFile.length() > 1_000_000_000L
    override fun modelStatus() =
        if (isModelReady()) "embedded model ${modelFile.length() / 1048576} MB"
        else "model not downloaded yet (first START downloads it)"
}

fun createServerManager(ctx: Context): ServerManager = EmbeddedServerManager(ctx)
