package com.bpoppa.pocketai

import fi.iki.elonen.NanoHTTPD
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLEncoder

/**
 * Tiny localhost gateway that lets a browser's address bar ask the on-device
 * model. Serves a streaming HTML answer page on 127.0.0.1:8182 so it can be
 * registered as a custom search engine (".../answer?q=%s"). Requests carrying
 * a foreign Origin/Referer are refused, and no CORS headers are ever sent, so
 * web pages cannot read responses - only top-level navigations work.
 */
class AnswerGateway(
    port: Int,
    private val llama: LlamaClient,
    private val onWake: () -> Unit,
    private val apiToken: String = ""
) : NanoHTTPD("127.0.0.1", port) {

    private val style = """
        <style>
          body{font-family:system-ui,sans-serif;background:#101418;color:#e8eaed;margin:0;padding:20px}
          .card{max-width:720px;margin:0 auto}
          .badge{display:inline-block;font-size:12px;color:#9ab0ff;background:#1c2440;
                 border-radius:12px;padding:4px 12px;margin-bottom:16px}
          .badge.web{color:#ffcf7a;background:#40311c}
          pre{white-space:pre-wrap;word-wrap:break-word;font-family:inherit;font-size:16px;line-height:1.55}
          .foot{margin-top:24px;font-size:12px;color:#5f6b7a}
        </style>
    """.trimIndent()

    override fun serve(session: IHTTPSession): Response {
        val origin = session.headers["origin"] ?: session.headers["referer"]
        if (origin != null &&
            !origin.startsWith("http://127.0.0.1") &&
            !origin.startsWith("http://localhost") &&
            !origin.startsWith("chrome-extension:") &&
            !origin.startsWith("moz-extension:")
        ) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")
        }
        return when (session.uri) {
            "/" -> newFixedLengthResponse(Response.Status.OK, "text/html", infoPage())
            "/health" -> newFixedLengthResponse(
                Response.Status.OK, "text/plain",
                if (llama.health()) "ok" else "waking"
            )
            "/answer" -> answer(session)
            "/ask" -> ask(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    /** Bearer-authenticated JSON endpoint for the browser extension. */
    private fun ask(session: IHTTPSession): Response {
        fun json(status: Response.Status, body: String): Response =
            newFixedLengthResponse(status, "application/json", body)
        if (session.method != Method.POST) {
            return json(Response.Status.METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}")
        }
        val auth = session.headers["authorization"] ?: ""
        if (apiToken.isEmpty() || auth != "Bearer $apiToken") {
            return json(Response.Status.UNAUTHORIZED, "{\"error\":\"bad or missing token\"}")
        }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val q = try {
            org.json.JSONObject(body).optString("q", "").trim()
        } catch (_: Exception) { "" }
        val web = try {
            org.json.JSONObject(body).optBoolean("web", false)
        } catch (_: Exception) { false }
        if (q.isEmpty()) return json(Response.Status.BAD_REQUEST, "{\"error\":\"empty query\"}")
        if (!llama.health()) {
            return json(Response.Status.SERVICE_UNAVAILABLE, "{\"error\":\"model not running - open the PocketAI app once\"}")
        }
        return try {
            val system = buildString {
                append(
                    "You are PocketAI, a private assistant. Answer concisely and truthfully. " +
                        "If unsure or missing current information, say so plainly. Prefer under 150 words."
                )
                if (web) append("\n\n").append(SearchHelper.contextFor(q, SearchHelper.search(q)))
            }
            val answer = llama.complete(
                listOf(ChatMsg("system", system), ChatMsg("user", q))
            )
            json(Response.Status.OK, org.json.JSONObject().put("answer", answer).toString())
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, "{\"error\":\"${escape(e.message ?: "?")}\"}")
        }
    }

    private fun infoPage(): String = """
        <!doctype html><html><head><meta charset="utf-8">$style</head><body><div class="card">
        <div class="badge">PocketAI gateway</div>
        <pre>Add this as a custom search engine:

http://127.0.0.1:8182/answer?q=%s        (on-device only)
http://127.0.0.1:8182/answer?q=%s&amp;web=1  (web-assisted)</pre>
        <div class="foot">local inference · no telemetry · queries stay on this phone</div>
        </div></body></html>
    """.trimIndent()

    private fun wakingPage(q: String, web: Boolean): String {
        val qs = URLEncoder.encode(q, "UTF-8")
        val webPart = if (web) "&amp;web=1" else ""
        return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta http-equiv="refresh" content="4;url=/answer?q=$qs$webPart">
        $style</head><body><div class="card">
        <div class="badge">PocketAI is waking up…</div>
        <pre>The local model takes a few seconds to load.
        This page refreshes automatically.</pre>
        </div></body></html>
        """.trimIndent()
    }

    private fun answer(session: IHTTPSession): Response {
        val q = (session.parameters["q"]?.firstOrNull() ?: "").trim()
        if (q.isEmpty()) {
            return newFixedLengthResponse(Response.Status.OK, "text/html", infoPage())
        }
        val web = session.parameters["web"]?.firstOrNull() == "1"
        if (!llama.health()) {
            Thread { try { onWake() } catch (_: Exception) {} }.start()
            return newFixedLengthResponse(Response.Status.OK, "text/html", wakingPage(q, web))
        }

        val pipedIn = PipedInputStream(64 * 1024)
        val pipedOut = PipedOutputStream(pipedIn)

        Thread {
            try {
                pipedOut.use { out ->
                    fun emit(s: String) {
                        out.write(s.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    val badge = if (web) {
                        "<div class=\"badge web\">PocketAI · web-assisted (DuckDuckGo)</div>"
                    } else {
                        "<div class=\"badge\">PocketAI · on-device</div>"
                    }
                    emit(
                        "<!doctype html><html><head><meta charset=\"utf-8\">" +
                            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                            style + "</head><body><div class=\"card\">" + badge + "<pre>"
                    )
                    val system = buildString {
                        append(
                            "You are PocketAI, a private assistant answering on a mobile browser page. " +
                                "Answer concisely and truthfully. If you are unsure, or the question needs " +
                                "current information you were not given, say so plainly. Prefer under 150 words."
                        )
                        if (web) {
                            val results = SearchHelper.search(q)
                            append("\n\n").append(SearchHelper.contextFor(q, results))
                        }
                    }
                    var used = 0L
                    try {
                        llama.completeStream(
                            listOf(ChatMsg("system", system), ChatMsg("user", q))
                        ) { token ->
                            used += 1
                            emit(escape(token))
                        }
                    } catch (e: Exception) {
                        emit("\n[generation error: ${escape(e.message ?: "?")}]")
                    }
                    if (used == 0L) emit("(no answer produced — try again in a moment)")
                    emit("</pre><div class=\"foot\">generated on this phone · $flavor</div></div></body></html>")
                }
            } catch (_: Exception) {
            }
        }.start()

        return newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", pipedIn)
    }

    private val flavor = "pocketai"

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
