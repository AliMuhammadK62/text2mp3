package com.alimuhammad.text2mp3.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFile {

    fun write(file: File, shorts: ShortArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            val dataLen = shorts.size.toLong() * 2
            out.write(header(sampleRate, dataLen))
            val buf = ByteArray(shorts.size * 2)
            for (i in shorts.indices) {
                val s = shorts[i].toInt()
                buf[2 * i] = (s and 0xFF).toByte()
                buf[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
            }
            out.write(buf)
        }
    }

    private fun header(rate: Int, dataLen: Long): ByteArray {
        val channels = 1; val bits = 16
        val byteRate = rate * channels * bits / 8
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((dataLen + 36).toInt())
        h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1)
        h.putShort(channels.toShort()); h.putInt(rate)
        h.putInt(byteRate); h.putShort((channels * bits / 8).toShort()); h.putShort(bits.toShort())
        h.put("data".toByteArray()); h.putInt(dataLen.toInt())
        return h.array()
    }
}
