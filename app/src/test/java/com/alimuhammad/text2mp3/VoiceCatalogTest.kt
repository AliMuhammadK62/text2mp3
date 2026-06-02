package com.alimuhammad.text2mp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {

    @Test fun parseSplitsLocaleNameQuality() {
        val m = VoiceCatalog.parse("en_US-hfc_female-medium")
        assertEquals("en_US", m.locale)
        assertEquals("hfc_female", m.name)
        assertEquals("medium", m.quality)
    }

    @Test fun parseHandlesMultiPartQuality() {
        val m = VoiceCatalog.parse("zh_CN-huayan-x_low")
        assertEquals("zh_CN", m.locale)
        assertEquals("x_low", m.quality)
    }

    @Test fun localeOfReturnsPrefix() {
        assertEquals("de_DE", VoiceCatalog.localeOf("de_DE-thorsten-low"))
    }

    @Test fun catalogueIsEnglishPlusMultilingual() {
        assertEquals(
            VoiceCatalog.ENGLISH_STEMS.size + VoiceCatalog.MULTILINGUAL_STEMS.size,
            VoiceCatalog.STEMS.size
        )
        assertTrue(VoiceCatalog.STEMS.contains(VoiceCatalog.DEFAULT_VOICE))
    }

    @Test fun languagesAreDistinctAndCoverMultiple() {
        val langs = VoiceCatalog.languages()
        assertEquals(langs.size, langs.distinct().size)
        assertTrue(langs.contains("en_US"))
        assertTrue(langs.contains("de_DE"))
        assertTrue(langs.contains("fr_FR"))
    }

    @Test fun languageLabelIsHumanReadable() {
        assertTrue(VoiceCatalog.languageLabel("de_DE").contains("German"))
    }

    @Test fun bundleUrlIsWellFormed() {
        val url = VoiceCatalog.bundleUrl("en_US-amy-low")
        assertTrue(url.startsWith("https://"))
        assertTrue(url.endsWith("vits-piper-en_US-amy-low.tar.bz2"))
    }
}
