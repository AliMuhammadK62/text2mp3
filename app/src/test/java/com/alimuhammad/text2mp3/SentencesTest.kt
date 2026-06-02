package com.alimuhammad.text2mp3

import com.alimuhammad.text2mp3.text.Sentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SentencesTest {

    @Test fun splitsIntoSentencesWithCorrectOffsets() {
        val text = "Hello world. How are you? I am fine."
        val spans = Sentences.split(text, Locale.US)
        assertEquals(3, spans.size)
        assertEquals("Hello world.", spans[0].text)
        assertEquals("How are you?", spans[1].text)
        assertEquals("I am fine.", spans[2].text)

        for (s in spans) assertEquals(s.text, text.substring(s.start, s.end))
    }

    @Test fun blankInputProducesNoSpans() {
        assertTrue(Sentences.split("   \n  ").isEmpty())
    }

    @Test fun splitForSynthesisRespectsMaxChars() {
        val long = "word ".repeat(200).trim()
        val spans = Sentences.splitForSynthesis(long, maxChars = 100, locale = Locale.US)
        assertTrue("expected multiple chunks", spans.size > 1)
        for (s in spans) {
            assertTrue("chunk too long: ${s.text.length}", s.text.length <= 100)

            assertTrue(s.start in 0..long.length && s.end in s.start..long.length)
        }
    }

    @Test fun shortSentenceNotSplit() {
        val spans = Sentences.splitForSynthesis("Just one short line.", maxChars = 600, locale = Locale.US)
        assertEquals(1, spans.size)
        assertEquals("Just one short line.", spans[0].text)
    }
}
