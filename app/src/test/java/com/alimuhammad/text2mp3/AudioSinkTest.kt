package com.alimuhammad.text2mp3

import com.alimuhammad.text2mp3.audio.AudioFormatType
import com.alimuhammad.text2mp3.audio.AudioSink
import com.alimuhammad.text2mp3.audio.WavFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class AudioSinkTest {

    private val rate = 22050

    private fun tone(samples: Int): ShortArray = ShortArray(samples) { i ->
        (Math.sin(2.0 * Math.PI * 440.0 * i / rate) * 8000).toInt().toShort()
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir") ?: ".").apply { mkdirs() }

    @Test fun wavSinkWritesValidRiffHeader() {
        val out = ByteArrayOutputStream()
        val sink = AudioSink.create(AudioFormatType.WAV, out, tempDir())
        sink.write(tone(1000), rate)
        sink.write(tone(1000), rate)
        sink.finish(); sink.close()

        val bytes = out.toByteArray()
        assertTrue("too small", bytes.size > 44 + 2000 * 2 - 4)
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])
        assertEquals('W'.code.toByte(), bytes[8])
        assertEquals('A'.code.toByte(), bytes[9])
        assertEquals('V'.code.toByte(), bytes[10])
        assertEquals('E'.code.toByte(), bytes[11])
    }

    @Test fun mp3SinkProducesMp3Bytes() {
        val out = ByteArrayOutputStream()
        val sink = AudioSink.create(AudioFormatType.MP3, out, tempDir())
        sink.write(tone(20000), rate)
        sink.finish(); sink.close()

        val bytes = out.toByteArray()
        assertTrue("mp3 too small: ${bytes.size}", bytes.size > 200)
        val frameSync = (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0
        val id3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        assertTrue("not an MP3 stream", frameSync || id3)
    }

    @Test fun wavFileWritesExpectedLength() {
        val f = File.createTempFile("wavfile", ".wav", tempDir())
        try {
            val n = 5000
            WavFile.write(f, tone(n), rate)
            assertEquals((44 + n * 2).toLong(), f.length())
            val head = ByteArray(4)
            f.inputStream().use { it.read(head) }
            assertEquals("RIFF", String(head))
        } finally { f.delete() }
    }

    @Test fun formatTypesHaveDistinctExtensions() {
        val exts = AudioFormatType.values().map { it.ext }
        assertEquals(exts.size, exts.distinct().size)
    }
}
