package com.paperfeed.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    const val USER_AGENT = "PaperFeed/1.0 (Android; journal reader)"
    const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Hard cap per request so a trickling server can't stall the UI indefinitely.
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun get(
        url: String,
        userAgent: String = USER_AGENT,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        // One retry for transient server errors (OpenAlex/Crossref occasionally answer 502/503/504/429).
        var attempt = 0
        var result: String? = null
        while (result == null) {
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                val transient = resp.code == 429 || resp.code in 500..599
                when {
                    resp.isSuccessful -> result = body
                    !transient || attempt >= 1 -> throw HttpException(resp.code, "HTTP ${resp.code} from ${req.url.host}")
                }
            }
            if (result == null) {
                attempt++
                delay(2000)
            }
        }
        result!!
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
