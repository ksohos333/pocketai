package com.bpoppa.pocketai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatMsg(val role: String, val content: String)

class LlamaClient(private val port: Int, private val apiKey: String = "") {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun authed(builder: okhttp3.Request.Builder): okhttp3.Request.Builder =
        if (apiKey.isEmpty()) builder else builder.header("Authorization", "Bearer $apiKey")

    fun health(): Boolean {
        return try {
            val req = authed(Request.Builder().url("http://127.0.0.1:$port/health")).build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    /** Sends [history] (system+prior turns+new user msg) and returns the assistant text. */
    fun complete(history: List<ChatMsg>): String {
        val messages = JSONArray()
        history.forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val body = JSONObject()
            .put("model", "pocketai")
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 1024)
            .put("stream", false)

        val req = authed(
            Request.Builder()
                .url("http://127.0.0.1:$port/v1/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
        ).build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("server ${resp.code}: ${resp.body?.string()?.take(300)}")
            val txt = resp.body?.string() ?: throw IOException("empty body")
            return JSONObject(txt)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    /** Streams the answer, invoking [onToken] per chunk; returns the full text. */
    fun completeStream(history: List<ChatMsg>, onToken: (String) -> Unit): String {
        val messages = JSONArray()
        history.forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val body = JSONObject()
            .put("model", "pocketai")
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 1024)
            .put("stream", true)

        val req = authed(
            Request.Builder()
                .url("http://127.0.0.1:$port/v1/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
        ).build()

        val sb = StringBuilder()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("server ${resp.code}: ${resp.body?.string()?.take(300)}")
            val source = resp.body?.source() ?: throw IOException("empty body")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                try {
                    val delta = JSONObject(payload)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .optJSONObject("delta") ?: continue
                    val piece = delta.optString("content", "")
                    if (piece.isNotEmpty() && piece != "null") {
                        sb.append(piece)
                        onToken(piece)
                    }
                } catch (_: Exception) {
                }
            }
        }
        return sb.toString().trim()
    }
}
