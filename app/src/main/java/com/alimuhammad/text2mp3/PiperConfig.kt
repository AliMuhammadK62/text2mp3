package com.alimuhammad.text2mp3

import android.content.Context
import java.io.File

class PiperConfig private constructor(
    val voicesDir: File,
    val cacheDir: File,
    val espeakDataDir: File,
    private val ctx: Context
) {
    private val prefs = ctx.getSharedPreferences("piper_config", Context.MODE_PRIVATE)

    val appContext: Context = ctx.applicationContext

    var defaultVoice: String
        get() = prefs.getString("default_voice", VoiceCatalog.DEFAULT_VOICE) ?: VoiceCatalog.DEFAULT_VOICE
        set(v) = prefs.edit().putString("default_voice", v).apply()

    var speakingRate: Float
        get() = prefs.getFloat("speaking_rate", 1.0f)
        set(v) = prefs.edit().putFloat("speaking_rate", v).apply()

    var pitch: Float
        get() = prefs.getFloat("pitch", 1.0f)
        set(v) = prefs.edit().putFloat("pitch", v.coerceIn(0.25f, 4.0f)).apply()

    var volume: Float
        get() = prefs.getFloat("volume", 1.0f)
        set(v) = prefs.edit().putFloat("volume", v.coerceIn(0.0f, 1.5f)).apply()

    var normalizeText: Boolean
        get() = prefs.getBoolean("normalize_text", true)
        set(v) = prefs.edit().putBoolean("normalize_text", v).apply()

    var sleepTimerMinutes: Int
        get() = prefs.getInt("sleep_timer_min", 0)
        set(v) = prefs.edit().putInt("sleep_timer_min", v).apply()

    var editorTextSizeSp: Float
        get() = prefs.getFloat("editor_text_sp", 16f)
        set(v) = prefs.edit().putFloat("editor_text_sp", v.coerceIn(12f, 30f)).apply()

    var noiseScale: Float
        get() = prefs.getFloat("noise_scale", 0.667f)
        set(v) = prefs.edit().putFloat("noise_scale", v).apply()

    var noiseWScale: Float
        get() = prefs.getFloat("noise_w_scale", 0.8f)
        set(v) = prefs.edit().putFloat("noise_w_scale", v).apply()

    var maxCacheFiles: Int
        get() = prefs.getInt("max_cache_files", 2000)
        set(v) = prefs.edit().putInt("max_cache_files", v).apply()

    var maxConcurrency: Int
        get() = prefs.getInt("max_concurrency", 2).coerceAtLeast(1)
        set(v) = prefs.edit().putInt("max_concurrency", v).apply()

    var retainAfterPlay: Boolean
        get() = prefs.getBoolean("retain_after_play", false)
        set(v) = prefs.edit().putBoolean("retain_after_play", v).apply()

    fun validate(): List<String> {
        val errors = ArrayList<String>()
        if (!File(espeakDataDir, "phontab").exists()) {
            errors.add("espeak-ng-data incomplete (phontab missing): $espeakDataDir")
        }
        if (!voicesDir.exists()) errors.add("VoicesDir not found: $voicesDir")
        if (speakingRate <= 0f) errors.add("SpeakingRate must be > 0.")
        if (maxConcurrency < 1) errors.add("MaxConcurrency must be >= 1.")
        return errors
    }

    companion object {
        fun resolve(ctx: Context): PiperConfig {
            val voices = File(ctx.filesDir, "piper_voices").apply { mkdirs() }
            val cache = File(ctx.cacheDir, "piper_cache").apply { mkdirs() }
            val espeak = File(ctx.filesDir, "espeak-ng-data")
            return PiperConfig(voices, cache, espeak, ctx.applicationContext)
        }
    }
}
