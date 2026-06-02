package com.alimuhammad.text2mp3

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class PiperCache(private val cacheDir: File, private val maxFiles: Int = 2000) {

    private val lock = ReentrantReadWriteLock()
    private val index = HashMap<String, String>()

    init {
        cacheDir.mkdirs()
        rebuildIndex()
    }

    fun tryGet(text: String, voiceStem: String): String? {
        val key = makeKey(text, voiceStem)
        var stale: String? = null
        lock.read {
            val path = index[key]
            if (path != null) {
                if (File(path).exists()) return path
                stale = key
            }
        }
        if (stale != null) lock.write { index.remove(stale) }
        return null
    }

    fun getTargetPath(text: String, voiceStem: String): String =
        File(cacheDir, makeKey(text, voiceStem) + ".wav").absolutePath

    fun commitFile(text: String, voiceStem: String, wavPath: String) {
        val key = makeKey(text, voiceStem)
        lock.write { index[key] = wavPath }
        if (maxFiles > 0) enforceLimit()
    }

    val count: Int get() = lock.read { index.size }

    val diskUsageBytes: Long
        get() = cacheDir.listFiles { _, n -> n.endsWith(".wav") }
            ?.sumOf { it.length() } ?: 0L

    fun clear() = lock.write {
        cacheDir.listFiles { _, n -> n.endsWith(".wav") }?.forEach { runCatching { it.delete() } }
        index.clear()
    }

    fun invalidate(text: String, voiceStem: String) = lock.write {
        val key = makeKey(text, voiceStem)
        index.remove(key)?.let { runCatching { File(it).delete() } }
    }

    private fun rebuildIndex() = lock.write {
        index.clear()
        cacheDir.listFiles { _, n -> n.endsWith(".wav") }?.forEach { f ->
            val key = f.nameWithoutExtension
            if (key.length == 32 && key.all { it.isHex() }) index[key] = f.absolutePath
        }
    }

    private fun enforceLimit() = lock.write {
        if (index.size <= maxFiles) return@write
        val files = (cacheDir.listFiles { _, n -> n.endsWith(".wav") } ?: return@write)
            .sortedBy { it.lastModified() }
        val toRemove = files.size - maxFiles
        for (i in 0 until toRemove) {
            runCatching {
                index.remove(files[i].nameWithoutExtension)
                files[i].delete()
            }
        }
    }

    private fun Char.isHex(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    companion object {
        private fun makeKey(text: String, voiceStem: String): String {
            val input = text.trim() + "|" + voiceStem.trim().lowercase()
            val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(32)
            for (i in 0 until 16) sb.append("%02x".format(hash[i]))
            return sb.toString()
        }
    }
}
