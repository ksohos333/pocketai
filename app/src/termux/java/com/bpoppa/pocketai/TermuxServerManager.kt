package com.bpoppa.pocketai

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Talks to llama-server running inside Termux (Owner profile).
 * Requires: Termux installed, ~/.termux/termux.properties has allow-external-apps=true,
 * and pkg install llama-cpp + model at ~/models/model.gguf.
 */
class TermuxServerManager(private val ctx: Context) : ServerManager {
    override val port = 8080
    override val flavorName = "termux"

    override fun ensureStarted(onLog: (String) -> Unit) {
        try {
            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/llama-server")
            intent.putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf(
                    "-m", "/data/data/com.termux/files/home/models/model.gguf",
                    "--host", "127.0.0.1", "--port", port.toString(),
                    "-c", "4096", "-t", "4", "--no-webui"
                )
            )
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            ctx.startService(intent)
            onLog("run command sent to Termux…")
        } catch (e: Exception) {
            onLog("Termux not reachable: ${e.message}")
            Toast.makeText(ctx, "Is Termux installed?", Toast.LENGTH_LONG).show()
        }
    }

    override fun stop() {
        // The server lives in Termux; PocketAI does not kill it on exit (shared instance).
    }

    override fun isModelReady() = true
    override fun modelStatus() = "model in Termux (~/models/model.gguf)"
}

fun createServerManager(ctx: Context): ServerManager = TermuxServerManager(ctx)
