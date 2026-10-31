package com.bpoppa.pocketai

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Local-only chat history, one JSONL file, no cloud, no telemetry. */
class ChatStore(context: Context) {
    private val file: File = File(context.filesDir, "chat.jsonl")

    fun append(msg: ChatMsg) {
        file.appendText(JSONObject().put("role", msg.role).put("content", msg.content).toString() + "\n")
    }

    fun load(limit: Int = 200): List<ChatMsg> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .takeLast(limit)
            .mapNotNull { line ->
                try {
                    val o = JSONObject(line)
                    ChatMsg(o.getString("role"), o.getString("content"))
                } catch (e: Exception) {
                    null
                }
            }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    /** Overwrites the file with random bytes before deleting, so nothing recoverable remains. */
    fun secureWipe() {
        if (!file.exists()) return
        try {
            val len = file.length()
            val random = java.security.SecureRandom()
            file.outputStream().use { out ->
                var left = len
                val buf = ByteArray(8192)
                while (left > 0) {
                    val n = minOf(buf.size.toLong(), left).toInt()
                    random.nextBytes(buf.copyOf(n))
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        } catch (_: Exception) {
        }
        file.delete()
    }

    /** Simple keyword-overlap retrieval over stored history, for the optional RAG memory. */
    fun search(query: String, k: Int = 6): List<ChatMsg> {
        val all = load(limit = 400)
        if (all.isEmpty()) return emptyList()
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 }.toSet()
        if (terms.isEmpty()) return emptyList()
        return all.asSequence()
            .map { m ->
                val lower = m.content.lowercase()
                m to terms.count { lower.contains(it) }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
            .toList()
    }
}
