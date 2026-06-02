package com.alimuhammad.text2mp3

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class VoiceRepository(private val voicesDir: File) {

    fun interface Progress {
        fun report(done: Long, total: Long, phase: String)
    }

    fun isInstalled(stem: String): Boolean {
        val d = File(voicesDir, stem)
        return File(d, "$stem.onnx").exists() && File(d, "tokens.txt").exists()
    }

    fun installedSizeBytes(stem: String): Long {
        val d = File(voicesDir, stem)
        if (!d.exists()) return 0
        return d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun delete(stem: String): Boolean = File(voicesDir, stem).deleteRecursively()

    @Throws(PiperException::class)
    fun download(stem: String, progress: Progress? = null) {
        val outDir = File(voicesDir, stem)
        val tmpTar = File(voicesDir, "$stem.tar.bz2.part")
        try {

            progress?.report(0, -1, "Connecting…")
            val conn = openFollowingRedirects(VoiceCatalog.bundleUrl(stem))
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmpTar.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress?.report(done, total, "Downloading")
                    }
                }
            }
            conn.disconnect()

            progress?.report(total, total, "Extracting")
            val staging = File(voicesDir, "$stem.staging").apply { deleteRecursively(); mkdirs() }
            extractModelFiles(tmpTar, stem, staging)

            if (!File(staging, "$stem.onnx").exists() || !File(staging, "tokens.txt").exists()) {
                throw PiperException("Bundle for $stem did not contain the expected model files.")
            }
            outDir.deleteRecursively()
            if (!staging.renameTo(outDir)) {
                staging.copyRecursively(outDir, overwrite = true)
                staging.deleteRecursively()
            }
            progress?.report(total, total, "Done")
        } catch (e: PiperException) {
            throw e
        } catch (e: Exception) {
            throw PiperException("Failed to install voice '$stem': ${e.message}", e)
        } finally {
            tmpTar.delete()
        }
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Text2MP3-Android")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) throw PiperException("Redirect with no Location header.")
                current = loc
                redirects++
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw PiperException("HTTP $code while downloading $url")
            }
            return conn
        }
    }

    private fun extractModelFiles(tarBz2: File, stem: String, staging: File) {
        val prefix = "${VoiceCatalog.bundleDirName(stem)}/"
        BufferedInputStream(tarBz2.inputStream()).use { fis ->
            BZip2CompressorInputStream(fis).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.removePrefix("./").removePrefix(prefix)

                            if (!name.contains('/') &&
                                (name == "$stem.onnx" || name == "tokens.txt" || name == "$stem.onnx.json")
                            ) {
                                File(staging, name).outputStream().use { tar.copyTo(it) }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
