package com.alimuhammad.text2mp3.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.alimuhammad.text2mp3.Piper
import com.alimuhammad.text2mp3.PiperVoice
import com.alimuhammad.text2mp3.text.Sentences
import java.util.Locale

class PiperTtsService : TextToSpeechService() {

    @Volatile private var stopRequested = false
    @Volatile private var currentStem: String = ""

    override fun onCreate() {
        super.onCreate()
        runCatching { Piper.init(applicationContext) }
        currentStem = runCatching { Piper.config.defaultVoice }.getOrDefault("")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        availabilityFor(lang, country)

    override fun onGetLanguage(): Array<String> {
        val loc = stemLocale(currentStem) ?: Locale.US
        return arrayOf(iso3(loc.language), iso3Country(loc.country), "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val avail = availabilityFor(lang, country)
        if (avail >= TextToSpeech.LANG_AVAILABLE) {
            resolveStem(lang, country)?.let { currentStem = it }
        }
        return avail
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        if (!Piper.isInitialised) runCatching { Piper.init(applicationContext) }

        val stem = resolveStem(request.language, request.country) ?: currentStem
        val voice: PiperVoice? = runCatching { Piper.engine.getVoice(stem) }.getOrNull()
        if (voice == null || !voice.modelExists) {
            callback.error()
            return
        }

        val text = request.charSequenceText?.toString()
            ?: @Suppress("DEPRECATION") request.text
            ?: ""
        if (text.isBlank()) { callback.done(); return }

        val reqRate = (request.speechRate.takeIf { it > 0 } ?: 100) / 100f
        val reqPitch = (request.pitch.takeIf { it > 0 } ?: 100) / 100f

        val speedMul = reqRate * runCatching { Piper.config.speakingRate }.getOrDefault(1.0f)
            .coerceAtLeast(0.05f)

        val sampleRate = voice.sampleRate
        callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        val maxBytes = callback.maxBufferSize.coerceAtLeast(512)

        try {
            val locale = stemLocale(stem) ?: Locale.getDefault()
            val sentences = Sentences.splitForSynthesis(text, maxChars = 600, locale = locale)
            val units = if (sentences.isEmpty())
                listOf(Sentences.Span(text, 0, text.length)) else sentences

            for (s in units) {
                if (stopRequested) { callback.done(); return }
                val pcm = Piper.engine.synthesizeFloat(
                    s.text, stem, speedMul = speedMul, pitch = reqPitch
                )
                val shorts = Piper.engine.floatToShort(pcm.samples, gain = 1.0f)
                if (!writePcm(callback, shorts, maxBytes)) { callback.done(); return }
            }
            callback.done()
        } catch (e: Exception) {
            runCatching { callback.error() }
        }
    }

    private fun writePcm(callback: SynthesisCallback, shorts: ShortArray, maxBytes: Int): Boolean {
        if (shorts.isEmpty()) return true
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val v = shorts[i].toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }

        val slice = (maxBytes - (maxBytes % 2)).coerceAtLeast(2)
        var off = 0
        while (off < bytes.size) {
            if (stopRequested) return false
            val n = minOf(slice, bytes.size - off)
            if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) return false
            off += n
        }
        return true
    }

    private fun availabilityFor(lang: String?, country: String?): Int {
        if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
        var langMatch = false
        for (v in installed()) {
            val loc = stemLocale(v.stem) ?: continue
            if (iso3(loc.language).equals(lang, true)) {
                langMatch = true
                if (country.isNullOrBlank() || iso3Country(loc.country).equals(country, true)) {
                    return TextToSpeech.LANG_COUNTRY_AVAILABLE
                }
            }
        }
        return if (langMatch) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun resolveStem(lang: String?, country: String?): String? {
        if (lang.isNullOrBlank()) return null
        var langOnly: String? = null
        for (v in installed()) {
            val loc = stemLocale(v.stem) ?: continue
            if (iso3(loc.language).equals(lang, true)) {
                if (country.isNullOrBlank() || iso3Country(loc.country).equals(country, true)) {
                    return v.stem
                }
                if (langOnly == null) langOnly = v.stem
            }
        }
        return langOnly
    }

    private fun installed(): List<PiperVoice> =
        runCatching { Piper.engine.installedVoices }.getOrDefault(emptyList())

    private fun stemLocale(stem: String): Locale? {
        val code = runCatching { Piper.engine.getVoice(stem)?.locale }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: stem.substringBefore('-').takeIf { it.isNotBlank() }
            ?: return null
        val parts = code.split('_', '-')
        return when {
            parts.size >= 2 -> Locale(parts[0], parts[1])
            parts.size == 1 -> Locale(parts[0])
            else -> null
        }
    }

    private fun iso3(language: String): String = try {
        Locale(language).isO3Language
    } catch (_: Exception) { language }

    private fun iso3Country(country: String): String = try {
        if (country.isBlank()) "" else Locale("", country).isO3Country
    } catch (_: Exception) { country }
}
