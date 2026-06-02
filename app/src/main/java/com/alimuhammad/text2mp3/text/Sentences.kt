package com.alimuhammad.text2mp3.text

import java.text.BreakIterator
import java.util.Locale

object Sentences {

    data class Span(val text: String, val start: Int, val end: Int)

    fun split(text: String, locale: Locale = Locale.getDefault()): List<Span> {
        if (text.isBlank()) return emptyList()
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val spans = ArrayList<Span>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val raw = text.substring(start, end)
            val trimmedStart = start + raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                spans.add(Span(trimmed, trimmedStart, trimmedStart + trimmed.length))
            }
            start = end
            end = it.next()
        }
        return spans
    }

    fun splitForSynthesis(text: String, maxChars: Int = 600, locale: Locale = Locale.getDefault()): List<Span> {
        val out = ArrayList<Span>()
        for (s in split(text, locale)) {
            if (s.text.length <= maxChars) { out.add(s); continue }
            var idx = 0
            while (idx < s.text.length) {
                var endRel = (idx + maxChars).coerceAtMost(s.text.length)
                if (endRel < s.text.length) {
                    val lastSpace = s.text.lastIndexOf(' ', endRel)
                    if (lastSpace > idx) endRel = lastSpace
                }
                val piece = s.text.substring(idx, endRel).trim()
                if (piece.isNotEmpty()) {
                    val absStart = s.start + idx
                    out.add(Span(piece, absStart, absStart + (endRel - idx)))
                }
                idx = endRel
            }
        }
        return out
    }
}
