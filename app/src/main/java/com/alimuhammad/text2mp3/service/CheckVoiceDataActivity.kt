package com.alimuhammad.text2mp3.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.alimuhammad.text2mp3.Piper
import java.util.Locale

class CheckVoiceDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Piper.init(applicationContext) }

        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()

        runCatching {
            for (v in Piper.engine.installedVoices) {
                localeTag(v.locale.ifBlank { v.stem.substringBefore('-') })?.let {
                    if (!available.contains(it)) available.add(it)
                }
            }
        }

        runCatching {
            for (stem in com.alimuhammad.text2mp3.VoiceCatalog.STEMS) {
                localeTag(stem.substringBefore('-'))?.let {
                    if (!available.contains(it) && !unavailable.contains(it)) unavailable.add(it)
                }
            }
        }
        if (available.isEmpty()) available.add("eng-USA")

        val result = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }

    private fun localeTag(code: String): String? {
        val parts = code.split('_', '-')
        return try {
            when {
                parts.size >= 2 -> {
                    val l = Locale(parts[0], parts[1])
                    "${l.isO3Language}-${l.isO3Country}"
                }
                parts.size == 1 && parts[0].isNotBlank() -> Locale(parts[0]).isO3Language
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
