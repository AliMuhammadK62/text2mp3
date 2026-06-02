package com.alimuhammad.text2mp3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alimuhammad.text2mp3.audio.AudioFormatType
import com.alimuhammad.text2mp3.audio.AudioSink
import com.alimuhammad.text2mp3.audio.TextToAudio
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioExportTest {

    private lateinit var ctx: Context
    private lateinit var stem: String
    private val text = "Exporting audio. This produces several segments so encoders are exercised."

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        Piper.init(ctx)
        val voices = Piper.engine.installedVoices
        assertTrue("no bundled voice installed", voices.isNotEmpty())
        stem = voices.first().stem
    }

    private fun export(type: AudioFormatType): ByteArray {
        val out = ByteArrayOutputStream()
        val sink = AudioSink.create(type, out, ctx.cacheDir)
        val segments = TextToAudio.convert(Piper.engine, text, stem, sink)
        assertTrue("no segments encoded", segments > 0)
        return out.toByteArray()
    }

    @Test fun exportsWav() {
        val b = export(AudioFormatType.WAV)
        assertTrue("wav too small", b.size > 44)
        assertTrue("not RIFF", String(b.copyOfRange(0, 4)) == "RIFF")
        assertTrue("not WAVE", String(b.copyOfRange(8, 12)) == "WAVE")
    }

    @Test fun exportsMp3() {
        val b = export(AudioFormatType.MP3)
        assertTrue("mp3 too small", b.size > 200)
        val sync = (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0
        val id3 = b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()
        assertTrue("not an MP3 stream", sync || id3)
    }

    @Test fun exportsM4a() {
        val b = export(AudioFormatType.M4A)
        assertTrue("m4a too small", b.size > 200)

        assertTrue("missing ftyp box", String(b.copyOfRange(4, 8)) == "ftyp")
    }
}
