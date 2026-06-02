package com.alimuhammad.text2mp3.text

import com.alimuhammad.text2mp3.PiperException
import java.net.HttpURLConnection
import java.net.URL

object WebExtractor {

    data class Article(val title: String, val text: String)

    private val TITLE_RE = Regex("""<title[^>]*>(.*?)</title>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ARTICLE_RE = Regex("""<(article|main)[^>]*>(.*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BODY_RE = Regex("""<body[^>]*>(.*?)</body>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    @Throws(PiperException::class)
    fun fetch(rawUrl: String): Article {
        val url = normalizeUrl(rawUrl)
        val html = download(url)

        val title = TITLE_RE.find(html)?.groupValues?.get(1)
            ?.let { HtmlText.toPlainText(it) }?.trim().orEmpty()

        val articleHtml = ARTICLE_RE.find(html)?.groupValues?.get(2)
            ?: BODY_RE.find(html)?.groupValues?.get(1)
            ?: html

        val text = HtmlText.toPlainText(articleHtml)
        if (text.isBlank()) throw PiperException("No readable text found at that URL.")
        return Article(title.ifBlank { url }, text)
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    private fun download(startUrl: String): String {
        var current = startUrl
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Android) Text2MP3/1.0 (article reader)")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw PiperException("Redirect without a location.")
                    current = URL(URL(current), loc).toString()
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK)
                    throw PiperException("HTTP $code fetching the page.")
                val charset = conn.contentEncoding
                    ?: conn.contentType?.substringAfter("charset=", "")?.ifBlank { null }
                    ?: "UTF-8"
                return conn.inputStream.bufferedReader(charsetForName(charset)).readText()
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun charsetForName(name: String): java.nio.charset.Charset = try {
        java.nio.charset.Charset.forName(name.trim())
    } catch (_: Exception) { Charsets.UTF_8 }
}
