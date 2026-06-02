package com.alimuhammad.text2mp3.text

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.alimuhammad.text2mp3.PiperException

object OcrReader {

    @Throws(PiperException::class)
    fun extract(ctx: Context, uri: Uri): String {
        val image = try {
            InputImage.fromFilePath(ctx, uri)
        } catch (e: Exception) {
            throw PiperException("Could not open the image: ${e.message}", e)
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(image))
            val text = result.text.trim()
            if (text.isBlank()) throw PiperException("No text was found in the image.")
            return text
        } catch (e: PiperException) {
            throw e
        } catch (e: Exception) {
            throw PiperException("OCR failed: ${e.message}", e)
        } finally {
            runCatching { recognizer.close() }
        }
    }
}
