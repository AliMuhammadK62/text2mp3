package com.alimuhammad.text2mp3

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object Mp3Output {

    class Target(
        val stream: OutputStream,
        val displayPath: String,
        val finalize: () -> Unit
    )

    fun openInDownloads(ctx: Context, baseName: String): Target {
        val name = ensureMp3Name(baseName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw PiperException("Could not create the file in Downloads.")
            val os = resolver.openOutputStream(uri)
                ?: throw PiperException("Could not open the Downloads file for writing.")
            Target(os, "Download/$name") {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = uniqueFile(dir, name)
            val os = FileOutputStream(file)
            Target(os, "Download/${file.name}") {
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("audio/mpeg"), null)
            }
        }
    }

    private fun ensureMp3Name(base: String): String {
        val cleaned = base.trim().ifEmpty { "piper_audio" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (cleaned.endsWith(".mp3", ignoreCase = true)) cleaned else "$cleaned.mp3"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "mp3")
        var i = 1
        while (f.exists()) { f = File(dir, "$stem ($i).$ext"); i++ }
        return f
    }
}
