package com.bpoppa.pocketai

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class WebResult(val title: String, val snippet: String, val url: String)

object SearchHelper {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** DuckDuckGo HTML endpoint - no API key, no Google. Returns top results. */
    fun search(query: String, max: Int = 5): List<WebResult> {
        val url = "https://html.duckduckgo.com/html/?q=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string() ?: return emptyList()
            val doc = Jsoup.parse(html)
            return doc.select("div.result, div.web-result").take(max).mapNotNull { r ->
                val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
                val sn = r.selectFirst(".result__snippet")
                WebResult(
                    title = a.text().trim(),
                    snippet = sn?.text()?.trim() ?: "",
                    url = a.absUrl("href").ifEmpty { a.attr("href") }
                )
            }
        }
    }

    /** Builds a system context string from results for the model. */
    fun contextFor(query: String, results: List<WebResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("Web search results for \"$query\" (DuckDuckGo, today):\n\n")
        results.forEachIndexed { i, r ->
            sb.append("[${i + 1}] ${r.title}\n${r.snippet}\nSource: ${r.url}\n\n")
        }
        sb.append("Use these results to answer the user's question. ")
        sb.append("Cite sources as [1], [2] where relevant. ")
        sb.append("If the results are irrelevant, say so and answer from your own knowledge.")
        return sb.toString()
    }
}
