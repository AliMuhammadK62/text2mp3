package com.alimuhammad.text2mp3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alimuhammad.text2mp3.text.Sentences
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TtsServicePathTest {

    private lateinit var ctx: Context
    private lateinit var stem: String

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        Piper.init(ctx)
        val voices = Piper.engine.installedVoices
        assertTrue("no bundled voice installed", voices.isNotEmpty())
        stem = voices.first().stem
    }

    @Test fun streamsPcmForMultiSentenceRequest() {
        val text = "First sentence here. Second sentence follows! And a third one?"
        val units = Sentences.splitForSynthesis(text, maxChars = 600, locale = Locale.US)
        assertTrue("expected several sentences", units.size >= 3)

        var totalBytes = 0
        for (s in units) {
            val pcm = Piper.engine.synthesizeFloat(s.text, stem, speedMul = 1.5f, pitch = 1.1f)
            val shorts = Piper.engine.floatToShort(pcm.samples, gain = 1.0f)
            totalBytes += shorts.size * 2
        }
        assertTrue("no audio streamed", totalBytes > 0)
    }

    @Test fun fasterRateYieldsShorterAudioThanSlower() {
        val text = "The quick brown fox jumps over the lazy dog repeatedly."
        val fast = Piper.engine.synthesizeFloat(text, stem, speedMul = 2.0f, pitch = 1.0f)
        val slow = Piper.engine.synthesizeFloat(text, stem, speedMul = 0.7f, pitch = 1.0f)
        assertTrue(
            "faster speech should be shorter (${fast.samples.size} vs ${slow.samples.size})",
            fast.samples.size < slow.samples.size
        )
    }
}
