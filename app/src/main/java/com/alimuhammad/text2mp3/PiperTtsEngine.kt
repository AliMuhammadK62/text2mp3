package com.alimuhammad.text2mp3

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.alimuhammad.text2mp3.text.PronunciationDict
import com.alimuhammad.text2mp3.text.TextNormalizer
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PiperTtsEngine(
    private val config: PiperConfig,
    private val log: ((String, Level) -> Unit)? = null
) {
    enum class Level { INFO, WARNING, ERROR }

    private val cache = PiperCache(config.cacheDir, config.maxCacheFiles)
    private val sessions = HashMap<String, OfflineTts>()
    private val lock = ReentrantLock()
    @Volatile private var voices: Map<String, PiperVoice> = emptyMap()

    init {
        config.validate().forEach { emit(it, Level.WARNING) }
        reloadVoices()
    }

    fun reloadVoices() {
        voices = PiperVoice.scanDirectory(config.voicesDir).associateBy { it.stem }
        emit("Loaded ${voices.size} installed voice(s) from ${config.voicesDir}", Level.INFO)
    }

    val installedVoices: List<PiperVoice> get() = voices.values.sortedBy { it.stem.lowercase() }

    fun getVoice(stem: String): PiperVoice? = voices[stem]

    @Throws(PiperException::class)
    fun getOrSynthesize(text: String, voiceStem: String = "", speakerId: Int = 0): String {
        if (text.isBlank()) throw PiperException("text must not be empty.")
        val stem = voiceStem.ifBlank { config.defaultVoice }

        cache.tryGet(text, stem)?.let { return it }

        lock.withLock {

            cache.tryGet(text, stem)?.let { return it }

            val voice = getVoice(stem) ?: throw PiperException("Voice not found: $stem")
            if (!voice.modelExists) throw PiperException("Voice model missing for $stem")

            val tts = getOrLoadSession(voice)
            val prepared = prepareText(text, voice)
            val speed = 1.0f / config.speakingRate.coerceAtLeast(0.05f)
            val audio = tts.generate(text = prepared, sid = speakerId, speed = speed)

            val outPath = cache.getTargetPath(text, stem)
            File(outPath).parentFile?.mkdirs()
            if (!audio.save(outPath)) throw PiperException("Failed to write WAV: $outPath")

            cache.commitFile(text, stem, outPath)
            emit("Synthesised [$stem] \"${truncate(text, 60)}\"", Level.INFO)
            return outPath
        }
    }

    class Pcm(val samples: ShortArray, val sampleRate: Int)

    class FloatPcm(val samples: FloatArray, val sampleRate: Int)

    @Throws(PiperException::class)
    fun synthesizeFloat(
        text: String,
        voiceStem: String = "",
        speedMul: Float = 1.0f,
        pitch: Float = config.pitch
    ): FloatPcm {
        val stem = voiceStem.ifBlank { config.defaultVoice }
        lock.withLock {
            val voice = getVoice(stem) ?: throw PiperException("Voice not found: $stem")
            if (!voice.modelExists) throw PiperException("Voice model missing for $stem")
            val tts = getOrLoadSession(voice)
            val prepared = prepareText(text, voice)
            if (prepared.isBlank()) return FloatPcm(FloatArray(0), voice.sampleRate)

            val p = pitch.coerceIn(0.25f, 4.0f)
            val baseTempo = (1.0f / config.speakingRate.coerceAtLeast(0.05f)) *
                speedMul.coerceAtLeast(0.05f)

            val sherpaSpeed = (baseTempo / p).coerceIn(0.1f, 10.0f)
            val audio = tts.generate(text = prepared, sid = 0, speed = sherpaSpeed)
            val samples = if (p != 1.0f) resample(audio.samples, p) else audio.samples
            return FloatPcm(samples, audio.sampleRate)
        }
    }

    @Throws(PiperException::class)
    fun synthesizeChunk(text: String, voiceStem: String = ""): Pcm {
        val f = synthesizeFloat(text, voiceStem, speedMul = 1.0f, pitch = config.pitch)
        return Pcm(floatToShort(f.samples, 1.0f), f.sampleRate)
    }

    private fun resample(src: FloatArray, factor: Float): FloatArray {
        if (src.isEmpty() || factor <= 0f) return src
        val newLen = (src.size / factor).toInt().coerceAtLeast(1)
        val out = FloatArray(newLen)
        val last = src.size - 1
        for (i in 0 until newLen) {
            val pos = i * factor
            val idx = pos.toInt()
            val frac = pos - idx
            val a = src[idx.coerceIn(0, last)]
            val b = src[(idx + 1).coerceIn(0, last)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    fun floatToShort(f: FloatArray, gain: Float = 1.0f): ShortArray {
        val out = ShortArray(f.size)
        for (i in f.indices) {
            var v = f[i] * gain * 32767.0f
            if (v > 32767.0f) v = 32767.0f
            if (v < -32768.0f) v = -32768.0f
            out[i] = v.toInt().toShort()
        }
        return out
    }

    private fun prepareText(text: String, voice: PiperVoice): String {
        var t = PronunciationDict.get(config.appContext).apply(text)
        if (config.normalizeText) {
            val english = voice.locale.startsWith("en", ignoreCase = true)
            t = TextNormalizer.normalize(t, enableNumberExpansion = english)
        }
        return t.ifBlank { text }
    }

    fun interface PreGenProgress { fun report(done: Int, total: Int) }

    fun preGenerate(texts: List<String>, voiceStem: String = "", progress: PreGenProgress? = null) {
        val stem = voiceStem.ifBlank { config.defaultVoice }
        val unique = texts.filter { it.isNotBlank() }.distinct()
        val toDo = unique.filter { cache.tryGet(it, stem) == null }
        emit("Pre-generating ${toDo.size} entries (${unique.size - toDo.size} cached) for [$stem]", Level.INFO)
        var done = 0
        for (t in toDo) {
            runCatching { getOrSynthesize(t, stem) }
                .onFailure { emit("Pre-generate failed for \"${truncate(t, 60)}\": ${it.message}", Level.WARNING) }
            done++
            progress?.report(done, toDo.size)
        }
        emit("Pre-generation complete. Cache: ${cache.count} files, ${cache.diskUsageBytes / 1024 / 1024} MB", Level.INFO)
    }

    private fun getOrLoadSession(voice: PiperVoice): OfflineTts {
        sessions[voice.stem]?.let { return it }
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = voice.onnxFile.absolutePath,
                    tokens = voice.tokensFile.absolutePath,
                    dataDir = config.espeakDataDir.absolutePath,
                    noiseScale = config.noiseScale,
                    noiseScaleW = config.noiseWScale,
                    lengthScale = 1.0f
                ),
                numThreads = config.maxConcurrency.coerceAtLeast(1),
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1
        )
        val tts = OfflineTts(assetManager = null, config = cfg)
        sessions[voice.stem] = tts
        return tts
    }

    fun evictAllSessions() = lock.withLock {
        sessions.values.forEach { runCatching { it.release() } }
        sessions.clear()
        emit("ONNX sessions evicted — new settings apply on next synthesis.", Level.INFO)
    }

    fun getCachedPath(text: String, voiceStem: String = ""): String? =
        cache.tryGet(text, voiceStem.ifBlank { config.defaultVoice })

    val cacheCount: Int get() = cache.count
    val cacheDiskUsageBytes: Long get() = cache.diskUsageBytes

    fun clearCache() {
        cache.clear()
        emit("Cache cleared.", Level.INFO)
    }

    fun statusSummary(): String = buildString {
        appendLine("Text2MP3 Status")
        appendLine("  Engine        : sherpa-onnx (ONNX Runtime + espeak-ng)")
        appendLine("  Voices dir    : ${config.voicesDir}")
        appendLine("  Voices loaded : ${voices.size} installed")
        appendLine("  Default voice : ${config.defaultVoice}")
        appendLine("  espeak-ng data: ${config.espeakDataDir}")
        appendLine("  Cache files   : ${cache.count} (${cache.diskUsageBytes / 1024 / 1024} MB)")
        appendLine("  Speaking rate : ${"%.2f".format(config.speakingRate)}")
        append("  Noise / W     : ${"%.3f".format(config.noiseScale)} / ${"%.3f".format(config.noiseWScale)}")
    }

    fun dispose() = lock.withLock {
        sessions.values.forEach { runCatching { it.release() } }
        sessions.clear()
    }

    private fun emit(msg: String, level: Level) = log?.invoke(msg, level)

    private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max) + "..."
}
