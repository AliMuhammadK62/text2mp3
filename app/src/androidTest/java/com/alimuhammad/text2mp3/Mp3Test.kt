package com.alimuhammad.text2mp3

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Mp3Test {

    @Test
    fun convertsTextToMp3InDownloads() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Piper.init(ctx)

        val voices = Piper.engine.installedVoices
        assertTrue("no bundled voice installed", voices.isNotEmpty())
        val stem = voices.first().stem

        val base = "piper_dltest_" + System.currentTimeMillis()
        val text = "Hello world. This is a Piper test of M P 3 export saved into Downloads. " +
                "It has several sentences so the converter emits multiple chunks."

        val target = Mp3Output.openInDownloads(ctx, base)
        val chunks = target.stream.use { os ->
            TextToMp3.convert(Piper.engine, text, stem, os)
        }
        target.finalize()
        assertTrue("no chunks encoded", chunks > 0)

        val bytes = readBackFromDownloads(ctx, "$base.mp3")
        assertTrue("file not found in Downloads", bytes != null)
        assertTrue("mp3 too small: ${bytes!!.size}", bytes.size > 1000)
        val frameSync = (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0
        val id3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        assertTrue("not a valid MP3", frameSync || id3)
    }

    private fun readBackFromDownloads(ctx: Context, name: String): ByteArray? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(name),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    return resolver.openInputStream(uri)?.use { it.readBytes() }
                }
            }
            return null
        } else {
            @Suppress("DEPRECATION")
            val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
            return if (f.exists()) f.readBytes() else null
        }
    }
}
