package com.alimuhammad.text2mp3

import net.sourceforge.lame.lowlevel.LameEncoder
import net.sourceforge.lame.mp3.MPEGMode
import java.io.OutputStream
import javax.sound.sampled.AudioFormat

object TextToMp3 {

    fun interface Progress {

        fun report(done: Int, total: Int)
    }

    private const val MAX_CHUNK_CHARS = 600
    private const val BITRATE_KBPS = 128
    private const val LAME_QUALITY = 5

    @Throws(PiperException::class)
    fun convert(
        engine: PiperTtsEngine,
        text: String,
        voiceStem: String,
        out: OutputStream,
        progress: Progress? = null,
        isCancelled: () -> Boolean = { false }
    ): Int {
        val chunks = chunk(text)
        if (chunks.isEmpty()) throw PiperException("The selected file has no readable text.")

        var encoder: LameEncoder? = null
        var mp3Buf = ByteArray(0)
        try {
            for ((i, c) in chunks.withIndex()) {
                if (isCancelled()) break
                val pcm = engine.synthesizeChunk(c, voiceStem)
                if (encoder == null) {
                    val fmt = AudioFormat(pcm.sampleRate.toFloat(), 16, 1, true, false)
                    encoder = LameEncoder(fmt, BITRATE_KBPS, MPEGMode.MONO, LAME_QUALITY, false)
                }

                val bytes = ByteArray(pcm.samples.size * 2)
                for (j in pcm.samples.indices) {
                    val s = pcm.samples[j].toInt()
                    bytes[2 * j] = (s and 0xFF).toByte()
                    bytes[2 * j + 1] = ((s shr 8) and 0xFF).toByte()
                }

                val need = (pcm.samples.size * 5) / 4 + 7200
                if (mp3Buf.size < need) mp3Buf = ByteArray(need)
                val n = encoder.encodeBuffer(bytes, 0, bytes.size, mp3Buf)
                if (n > 0) out.write(mp3Buf, 0, n)
                progress?.report(i + 1, chunks.size)
            }
            val enc = encoder ?: throw PiperException("Nothing was synthesised.")
            val tail = enc.encodeFinish(mp3Buf)
            if (tail > 0) out.write(mp3Buf, 0, tail)
            out.flush()
            return chunks.size
        } catch (e: PiperException) {
            throw e
        } catch (e: Exception) {
            throw PiperException("MP3 conversion failed: ${e.message}", e)
        } finally {
            try { encoder?.close() } catch (_: Exception) {}
        }
    }

    fun chunk(text: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) result.add(t)
            sb.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            sb.append(ch)
            val atSentenceEnd = ch == '.' || ch == '!' || ch == '?' || ch == '\n'
            if ((atSentenceEnd && sb.length >= MAX_CHUNK_CHARS / 2) || sb.length >= MAX_CHUNK_CHARS) {

                if (!atSentenceEnd) {
                    val lastSpace = sb.lastIndexOf(" ")
                    if (lastSpace > MAX_CHUNK_CHARS / 4) {
                        val carry = sb.substring(lastSpace + 1)
                        sb.setLength(lastSpace)
                        flush()
                        sb.append(carry)
                        i++
                        continue
                    }
                }
                flush()
            }
            i++
        }
        flush()
        return result
    }
}
