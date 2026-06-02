package com.alimuhammad.text2mp3

import com.alimuhammad.text2mp3.text.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test fun numberToWords_basics() {
        assertEquals("zero", TextNormalizer.numberToWords(0))
        assertEquals("nineteen", TextNormalizer.numberToWords(19))
        assertEquals("twenty", TextNormalizer.numberToWords(20))
        assertEquals("twenty-one", TextNormalizer.numberToWords(21))
        assertEquals("one hundred", TextNormalizer.numberToWords(100))
        assertEquals("one hundred five", TextNormalizer.numberToWords(105))
        assertEquals("one thousand two hundred thirty-four", TextNormalizer.numberToWords(1234))
        assertEquals("one million", TextNormalizer.numberToWords(1_000_000))
    }

    @Test fun groupedNumbersExpand() {
        val out = TextNormalizer.normalize("I counted 1,234 sheep.")
        assertTrue(out, out.contains("one thousand two hundred thirty-four"))
    }

    @Test fun smallNumbersAndYearsLeftForEspeak() {

        val out = TextNormalizer.normalize("In 2024 I ate 42 apples.")
        assertTrue(out, out.contains("2024"))
        assertTrue(out, out.contains("42"))
    }

    @Test fun currencyIsSpokenOut() {
        assertTrue(TextNormalizer.normalize("It costs \$5.50 total.").contains("five dollars and fifty cents"))
        assertTrue(TextNormalizer.normalize("Pay £20 now.").contains("twenty pounds"))
        assertTrue(TextNormalizer.normalize("Only €3 left.").contains("three euros"))
    }

    @Test fun abbreviationsExpand() {
        assertTrue(TextNormalizer.normalize("Dr. Smith arrived.").contains("Doctor Smith"))
        assertTrue(TextNormalizer.normalize("Use water, e.g. cold.").contains("for example"))
        assertTrue(TextNormalizer.normalize("Cats vs. dogs.").contains("versus"))
    }

    @Test fun urlsReducedToHost() {
        val out = TextNormalizer.normalize("See https://example.com/path?q=1 today.")
        assertTrue(out, out.contains("example.com"))
        assertTrue(out, !out.contains("/path"))
        assertTrue(out, !out.contains("https"))
    }

    @Test fun numberExpansionCanBeDisabled() {
        val out = TextNormalizer.normalize("Total 1,234 items.", enableNumberExpansion = false)
        assertTrue(out, out.contains("1,234"))
    }
}
