package com.alimuhammad.text2mp3

import org.json.JSONObject
import java.io.File

class PiperVoice private constructor(
    val stem: String,
    val dir: File
) {
    val onnxFile: File = File(dir, "$stem.onnx")
    val tokensFile: File = File(dir, "tokens.txt")
    val jsonFile: File = File(dir, "$stem.onnx.json")

    var sampleRate: Int = 22050; private set
    var numSpeakers: Int = 1; private set
    var locale: String = ""; private set
    var quality: String = "medium"; private set
    var defaultNoiseScale: Float = 0.667f; private set
    var defaultLengthScale: Float = 1.0f; private set
    var defaultNoiseW: Float = 0.8f; private set

    val modelExists: Boolean get() = onnxFile.exists() && tokensFile.exists()

    val modelSizeMB: Double
        get() = if (onnxFile.exists()) onnxFile.length() / (1024.0 * 1024.0) else 0.0

    private fun parseJson() {
        if (!jsonFile.exists()) {
            VoiceCatalog.parse(stem).let { locale = it.locale; quality = it.quality }
            return
        }
        try {
            val root = JSONObject(jsonFile.readText())
            root.optJSONObject("audio")?.let { sampleRate = it.optInt("sample_rate", sampleRate) }
            quality = root.optString("quality", quality)
            numSpeakers = root.optInt("num_speakers", numSpeakers)
            root.optJSONObject("language")?.let { locale = it.optString("code", locale) }
            root.optJSONObject("inference")?.let {
                defaultNoiseScale = it.optDouble("noise_scale", defaultNoiseScale.toDouble()).toFloat()
                defaultLengthScale = it.optDouble("length_scale", defaultLengthScale.toDouble()).toFloat()
                defaultNoiseW = it.optDouble("noise_w", defaultNoiseW.toDouble()).toFloat()
            }
            if (locale.isEmpty()) locale = VoiceCatalog.parse(stem).locale
        } catch (_: Exception) {
            VoiceCatalog.parse(stem).let { locale = it.locale; quality = it.quality }
        }
    }

    override fun toString(): String {
        val multi = if (numSpeakers > 1) ", $numSpeakers speakers" else ""
        return "$stem ($locale, $quality, $sampleRate Hz$multi)"
    }

    companion object {
        fun load(dir: File): PiperVoice {
            val stem = dir.name
            return PiperVoice(stem, dir).also { it.parseJson() }
        }

        fun scanDirectory(voicesDir: File): List<PiperVoice> {
            if (!voicesDir.exists()) return emptyList()
            return voicesDir.listFiles { f -> f.isDirectory }
                ?.mapNotNull { runCatching { load(it) }.getOrNull() }
                ?.filter { it.modelExists }
                ?.sortedBy { it.stem.lowercase() }
                ?: emptyList()
        }
    }
}
