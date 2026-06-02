package com.alimuhammad.text2mp3

import android.content.Context
import java.io.File

object BundledVoices {

    private const val ASSET_ROOT = "voices"
    private const val STAMP = ".bundled_v1"

    val BUNDLED_STEMS = listOf("en_US-amy-low", "en_GB-alan-low")

    fun ensureInstalled(ctx: Context, voicesDir: File) {
        val stamp = File(voicesDir, STAMP)
        if (stamp.exists()) return
        voicesDir.mkdirs()
        val am = ctx.assets
        val stems = am.list(ASSET_ROOT) ?: emptyArray()
        for (stem in stems) {
            val files = am.list("$ASSET_ROOT/$stem") ?: continue
            if (files.isEmpty()) continue
            val outDir = File(voicesDir, stem).apply { mkdirs() }
            for (f in files) {
                am.open("$ASSET_ROOT/$stem/$f").use { input ->
                    File(outDir, f).outputStream().use { input.copyTo(it) }
                }
            }
        }
        stamp.writeText("ok")
    }
}
