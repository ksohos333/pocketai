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
}
