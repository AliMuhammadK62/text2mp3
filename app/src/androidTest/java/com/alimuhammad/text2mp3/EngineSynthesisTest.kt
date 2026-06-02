package com.alimuhammad.text2mp3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineSynthesisTest {

    private lateinit var ctx: Context
    private lateinit var stem: String

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        Piper.init(ctx)
        val voices = Piper.engine.installedVoices
        assertTrue("no bundled voice installed", voices.isNotEmpty())
        stem = voices.first().stem
    }

    @Test fun synthesizeFloatProducesAudioAtVoiceRate() {
        val voice = Piper.engine.getVoice(stem)!!
        val pcm = Piper.engine.synthesizeFloat("Hello from Piper.", stem)
        assertTrue("no samples produced", pcm.samples.isNotEmpty())
        assertEquals(voice.sampleRate, pcm.sampleRate)
    }

    @Test fun floatToShortMatchesLengthAndClamps() {
        val floats = floatArrayOf(0f, 0.5f, -0.5f, 2f, -2f)
        val shorts = Piper.engine.floatToShort(floats, gain = 1.0f)
        assertEquals(floats.size, shorts.size)
        assertEquals(32767, shorts[3].toInt())
        assertEquals(-32768, shorts[4].toInt())
    }

    @Test fun pitchShiftStillProducesAudio() {
        val low = Piper.engine.synthesizeFloat("Pitch test sentence.", stem, pitch = 0.75f)
        val high = Piper.engine.synthesizeFloat("Pitch test sentence.", stem, pitch = 1.5f)
        assertTrue(low.samples.isNotEmpty())
        assertTrue(high.samples.isNotEmpty())
    }

    @Test fun getOrSynthesizeWritesValidWav() {
        val path = Piper.engine.getOrSynthesize("Cache me if you can.", stem)
        val f = File(path)
        assertTrue("wav not written", f.exists() && f.length() > 44)
        val head = ByteArray(4)
        f.inputStream().use { it.read(head) }
        assertEquals("RIFF", String(head))

        val again = Piper.engine.getOrSynthesize("Cache me if you can.", stem)
        assertEquals(path, again)
    }

    @Test fun synthesizeChunkReturnsPcm() {
        val pcm = Piper.engine.synthesizeChunk("Chunked synthesis.", stem)
        assertTrue(pcm.samples.isNotEmpty())
        assertTrue(pcm.sampleRate > 0)
    }
}
