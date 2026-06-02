package com.alimuhammad.text2mp3

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

object VoiceImporter {

    @Throws(PiperException::class)
    fun importFromTree(ctx: Context, treeUri: Uri, voicesDir: File): String {
        val tree = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw PiperException("Could not open the selected folder.")
        val files = tree.listFiles()

        val onnx = files.firstOrNull { (it.name ?: "").endsWith(".onnx", true) }
            ?: throw PiperException("No .onnx model file found in that folder.")
        val tokens = files.firstOrNull { (it.name ?: "").equals("tokens.txt", true) }
            ?: throw PiperException("No tokens.txt found in that folder.")
        val onnxName = onnx.name ?: throw PiperException("Model file has no name.")
        val stem = onnxName.removeSuffix(".onnx").removeSuffix(".ONNX")
        val json = files.firstOrNull { (it.name ?: "").equals("$stem.onnx.json", true) }
            ?: files.firstOrNull { (it.name ?: "").endsWith(".onnx.json", true) }

        val staging = File(voicesDir, "$stem.import").apply { deleteRecursively(); mkdirs() }
        try {
            copy(ctx, onnx.uri, File(staging, "$stem.onnx"))
            copy(ctx, tokens.uri, File(staging, "tokens.txt"))
            json?.let { copy(ctx, it.uri, File(staging, "$stem.onnx.json")) }

            if (!File(staging, "$stem.onnx").exists() || !File(staging, "tokens.txt").exists())
                throw PiperException("Import failed to copy the model files.")

            val outDir = File(voicesDir, stem)
            outDir.deleteRecursively()
            if (!staging.renameTo(outDir)) {
                staging.copyRecursively(outDir, overwrite = true)
                staging.deleteRecursively()
            }
            return stem
        } catch (e: PiperException) {
            staging.deleteRecursively(); throw e
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw PiperException("Voice import failed: ${e.message}", e)
        }
    }

    private fun copy(ctx: Context, src: Uri, dst: File) {
        ctx.contentResolver.openInputStream(src)?.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        } ?: throw PiperException("Could not read ${dst.name}.")
    }
}
