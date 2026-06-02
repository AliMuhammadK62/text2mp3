package com.alimuhammad.text2mp3.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.alimuhammad.text2mp3.PiperException
import net.sourceforge.lame.lowlevel.LameEncoder
import net.sourceforge.lame.mp3.MPEGMode
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.sound.sampled.AudioFormat

enum class AudioFormatType(val ext: String, val mime: String, val label: String) {
    MP3("mp3", "audio/mpeg", "MP3"),
    WAV("wav", "audio/wav", "WAV (uncompressed)"),
    M4A("m4a", "audio/mp4", "M4A / AAC")
}

interface AudioSink {
    fun write(shorts: ShortArray, sampleRate: Int)

    fun finish()
    fun close()

    companion object {
        fun create(type: AudioFormatType, out: OutputStream, tempDir: File): AudioSink = when (type) {
            AudioFormatType.MP3 -> Mp3Sink(out)
            AudioFormatType.WAV -> WavSink(out, tempDir)
            AudioFormatType.M4A -> AacSink(out, tempDir)
        }
    }
}

private class Mp3Sink(private val out: OutputStream) : AudioSink {
    private var encoder: LameEncoder? = null
    private var buf = ByteArray(0)
    private val bitrate = 128
    private val quality = 5

    override fun write(shorts: ShortArray, sampleRate: Int) {
        if (shorts.isEmpty()) return
        val enc = encoder ?: LameEncoder(
            AudioFormat(sampleRate.toFloat(), 16, 1, true, false),
            bitrate, MPEGMode.MONO, quality, false
        ).also { encoder = it }
        val bytes = shortsToLe(shorts)
        val need = (shorts.size * 5) / 4 + 7200
        if (buf.size < need) buf = ByteArray(need)
        val n = enc.encodeBuffer(bytes, 0, bytes.size, buf)
        if (n > 0) out.write(buf, 0, n)
    }

    override fun finish() {
        val enc = encoder ?: throw PiperException("Nothing was synthesised.")
        val tail = enc.encodeFinish(buf)
        if (tail > 0) out.write(buf, 0, tail)
        out.flush()
    }

    override fun close() { runCatching { encoder?.close() } }
}

private class WavSink(private val out: OutputStream, tempDir: File) : AudioSink {
    private val temp = File.createTempFile("pcm", ".raw", tempDir)
    private val pcm = temp.outputStream().buffered()
    private var sampleRate = 0
    private var dataBytes = 0L

    override fun write(shorts: ShortArray, sampleRate: Int) {
        if (this.sampleRate == 0) this.sampleRate = sampleRate
        if (shorts.isEmpty()) return
        val bytes = shortsToLe(shorts)
        pcm.write(bytes)
        dataBytes += bytes.size
    }

    override fun finish() {
        pcm.flush(); pcm.close()
        if (sampleRate == 0) throw PiperException("Nothing was synthesised.")
        out.write(wavHeader(sampleRate, dataBytes))
        temp.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    override fun close() { runCatching { pcm.close() }; runCatching { temp.delete() } }

    private fun wavHeader(rate: Int, dataLen: Long): ByteArray {
        val channels = 1; val bits = 16
        val byteRate = rate * channels * bits / 8
        val totalDataLen = dataLen + 36
        val h = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(totalDataLen.toInt())
        h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1)
        h.putShort(channels.toShort()); h.putInt(rate)
        h.putInt(byteRate); h.putShort((channels * bits / 8).toShort()); h.putShort(bits.toShort())
        h.put("data".toByteArray()); h.putInt(dataLen.toInt())
        return h.array()
    }
}

private class AacSink(private val out: OutputStream, tempDir: File) : AudioSink {
    private val temp = File.createTempFile("aac", ".m4a", tempDir)
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var sampleRate = 0
    private var presentationUs = 0L
    private val bufInfo = MediaCodec.BufferInfo()

    override fun write(shorts: ShortArray, sampleRate: Int) {
        if (shorts.isEmpty()) return
        if (codec == null) start(sampleRate)
        val c = codec!!
        val bytes = shortsToLe(shorts)
        var offset = 0
        while (offset < bytes.size) {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val inBuf = c.getInputBuffer(inIdx)!!
                inBuf.clear()
                val n = minOf(inBuf.capacity(), bytes.size - offset)
                inBuf.put(bytes, offset, n)
                c.queueInputBuffer(inIdx, 0, n, presentationUs, 0)
                presentationUs += n.toLong() * 1_000_000L / (2L * this.sampleRate)
                offset += n
            }
            drain(false)
        }
    }

    override fun finish() {
        val c = codec ?: throw PiperException("Nothing was synthesised.")
        val inIdx = c.dequeueInputBuffer(10_000)
        if (inIdx >= 0) c.queueInputBuffer(inIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)

        temp.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun start(rate: Int) {
        sampleRate = rate
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufInfo, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start(); muxerStarted = true
                }
                outIdx >= 0 -> {
                    val encoded = c.getOutputBuffer(outIdx)!!
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufInfo.size = 0
                    if (bufInfo.size > 0 && muxerStarted) {
                        encoded.position(bufInfo.offset)
                        encoded.limit(bufInfo.offset + bufInfo.size)
                        m.writeSampleData(trackIndex, encoded, bufInfo)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!endOfStream) return
            }
        }
    }

    override fun close() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        runCatching { if (muxerStarted) muxer?.stop() }; runCatching { muxer?.release() }
        runCatching { temp.delete() }
    }
}

private fun shortsToLe(shorts: ShortArray): ByteArray {
    val bytes = ByteArray(shorts.size * 2)
    for (i in shorts.indices) {
        val s = shorts[i].toInt()
        bytes[2 * i] = (s and 0xFF).toByte()
        bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return bytes
}
