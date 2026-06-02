package com.alimuhammad.text2mp3

import android.content.Context
import java.io.File

object EspeakAssets {

    private const val ASSET_DIR = "espeak-ng-data"
    private const val STAMP = ".copied_v1"

    fun ensureInstalled(ctx: Context, target: File) {
        val stamp = File(target, STAMP)
        if (stamp.exists() && File(target, "phontab").exists()) return
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        copyAssetDir(ctx, ASSET_DIR, target)
        stamp.writeText("ok")
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, outDir: File) {
        val am = ctx.assets
        outDir.mkdirs()
        val children = am.list(assetPath) ?: return
        for (child in children) {
            val childAsset = "$assetPath/$child"

            val grandChildren = am.list(childAsset)
            if (grandChildren.isNullOrEmpty()) {
                am.open(childAsset).use { input ->
                    File(outDir, child).outputStream().use { input.copyTo(it) }
                }
            } else {
                copyAssetDir(ctx, childAsset, File(outDir, child))
            }
        }
    }
}
