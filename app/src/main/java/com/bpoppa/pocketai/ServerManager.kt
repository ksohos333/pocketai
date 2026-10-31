package com.bpoppa.pocketai

interface ServerManager {
    val port: Int
    val flavorName: String
    val apiKey: String get() = ""
    fun ensureStarted(onLog: (String) -> Unit)
    fun stop()
    fun isModelReady(): Boolean
    fun modelStatus(): String
}
