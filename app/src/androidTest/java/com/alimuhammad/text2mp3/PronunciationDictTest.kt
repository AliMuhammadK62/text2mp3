package com.alimuhammad.text2mp3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alimuhammad.text2mp3.text.PronunciationDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PronunciationDictTest {

    private lateinit var dict: PronunciationDict

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        dict = PronunciationDict.get(ctx)
        dict.clear()
    }

    @Test fun appliesWholeWordCaseInsensitiveSubstitution() {
        dict.put("GIF", "jiff")
        assertEquals("a jiff file", dict.apply("a gif file"))
        assertEquals("a jiff file", dict.apply("a GIF file"))

        assertEquals("gifted person", dict.apply("gifted person"))
    }

    @Test fun longerPhrasesWinOverSubWords() {
        dict.put("New York", "New-York-City")
        dict.put("York", "Yorkshire")
        assertTrue(dict.apply("I love New York").contains("New-York-City"))
    }

    @Test fun removeAndClearWork() {
        dict.put("foo", "bar")
        assertEquals("bar", dict.apply("foo"))
        dict.remove("foo")
        assertEquals("foo", dict.apply("foo"))
        dict.put("a", "b")
        assertFalse(dict.isEmpty())
        dict.clear()
        assertTrue(dict.isEmpty())
    }

    @Test fun emptyDictionaryIsIdentity() {
        assertEquals("unchanged text", dict.apply("unchanged text"))
    }
}
