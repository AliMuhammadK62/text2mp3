package com.alimuhammad.text2mp3.text

object TextNormalizer {

    fun normalize(input: String, enableNumberExpansion: Boolean = true): String {
        var t = input
        t = stripUrls(t)
        t = expandAbbreviations(t)
        if (enableNumberExpansion) {
            t = expandCurrency(t)
            t = expandNumbers(t)
        }
        t = collapseWhitespace(t)
        return t
    }

    private val URL_RE = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private fun stripUrls(t: String): String = URL_RE.replace(t) { m ->

        val host = m.value
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
        " $host "
    }

    private val ABBREV = mapOf(
        "Dr." to "Doctor", "Mr." to "Mister", "Mrs." to "Misses", "Ms." to "Miss",
        "Prof." to "Professor", "St." to "Saint", "vs." to "versus", "etc." to "etcetera",
        "e.g." to "for example", "i.e." to "that is", "approx." to "approximately",
        "Inc." to "Incorporated", "Ltd." to "Limited", "Jr." to "Junior", "Sr." to "Senior",
        "No." to "Number", "Fig." to "Figure", "Dept." to "Department"
    )
    private fun expandAbbreviations(t: String): String {
        var s = t
        for ((k, v) in ABBREV) {
            s = s.replace(Regex("(?<![\\w])" + Regex.escape(k)), v)
        }
        return s
    }

    private val CURRENCY_RE = Regex("""([$£€])\s?([\d,]+)(?:\.(\d{1,2}))?""")
    private fun expandCurrency(t: String): String = CURRENCY_RE.replace(t) { m ->
        val unit = when (m.groupValues[1]) {
            "$" -> "dollars"; "£" -> "pounds"; "€" -> "euros"; else -> ""
        }
        val whole = m.groupValues[2].replace(",", "").toLongOrNull() ?: return@replace m.value
        val cents = m.groupValues[3]
        val main = "${numberToWords(whole)} $unit"
        if (cents.isNotEmpty() && cents != "00") {
            val c = cents.padEnd(2, '0').take(2).toLong()
            val centUnit = if (unit == "pounds") "pence" else "cents"
            " $main and ${numberToWords(c)} $centUnit "
        } else " $main "
    }

    private val NUMBER_RE = Regex("""(?<![\w.])(\d{1,3}(?:,\d{3})+|\d+)(?![\w.])""")
    private fun expandNumbers(t: String): String = NUMBER_RE.replace(t) { m ->
        val digits = m.value.replace(",", "")
        val n = digits.toLongOrNull() ?: return@replace m.value

        if (digits.length in 1..4 && !m.value.contains(",")) m.value
        else " ${numberToWords(n)} "
    }

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )
    private val SCALES = listOf(
        1_000_000_000_000L to "trillion",
        1_000_000_000L to "billion",
        1_000_000L to "million",
        1_000L to "thousand"
    )

    fun numberToWords(value: Long): String {
        if (value < 0) return "minus " + numberToWords(-value)
        if (value < 20) return ONES[value.toInt()]
        if (value < 100) {
            val t = TENS[(value / 10).toInt()]
            val r = (value % 10).toInt()
            return if (r == 0) t else "$t-${ONES[r]}"
        }
        if (value < 1000) {
            val h = (value / 100).toInt()
            val r = value % 100
            return if (r == 0L) "${ONES[h]} hundred"
            else "${ONES[h]} hundred ${numberToWords(r)}"
        }
        for ((scale, name) in SCALES) {
            if (value >= scale) {
                val major = value / scale
                val r = value % scale
                val head = "${numberToWords(major)} $name"
                return if (r == 0L) head else "$head ${numberToWords(r)}"
            }
        }
        return value.toString()
    }

    private fun collapseWhitespace(t: String): String =
        t.replace(Regex("[ \\t]{2,}"), " ").trim()
}
