package com.alimuhammad.text2mp3.text

object HtmlText {

    private val SCRIPT_STYLE = Regex(
        """<(script|style|head|noscript)[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val BLOCK_TAGS = Regex(
        """</?(p|div|br|li|ul|ol|h[1-6]|tr|table|section|article|header|footer|blockquote|pre)[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val ANY_TAG = Regex("""<[^>]+>""")
    private val MULTISPACE = Regex("""[ \t]{2,}""")
    private val MULTINEWLINE = Regex("""\n{3,}""")

    fun toPlainText(html: String): String {
        var s = html
        s = SCRIPT_STYLE.replace(s, " ")
        s = BLOCK_TAGS.replace(s, "\n")
        s = ANY_TAG.replace(s, "")
        s = decodeEntities(s)
        s = MULTISPACE.replace(s, " ")
        s = s.lineSequence().map { it.trim() }.joinToString("\n")
        s = MULTINEWLINE.replace(s, "\n\n")
        return s.trim()
    }

    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°"
    )
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

    private fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.substring(2).toIntOrNull(16)?.let { cp -> safeChar(cp) } ?: m.value
            body.startsWith("#") ->
                body.substring(1).toIntOrNull()?.let { cp -> safeChar(cp) } ?: m.value
            else -> NAMED[body.lowercase()] ?: m.value
        }
    }

    private fun safeChar(cp: Int): String =
        if (cp in 0..0x10FFFF) String(Character.toChars(cp)) else ""
}
