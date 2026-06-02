package com.alimuhammad.text2mp3.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class GetSampleTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = intent?.getStringExtra("language") ?: "eng"
        val sample = SAMPLES[lang] ?: "This is Text2MP3, speaking on your device."
        val result = Intent().apply {
            putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
        }
        setResult(TextToSpeech.LANG_AVAILABLE, result)
        finish()
    }

    companion object {
        private val SAMPLES = mapOf(
            "eng" to "This is Text2MP3, speaking on your device.",
            "deu" to "Dies ist Text2MP3, das auf Ihrem Gerät spricht.",
            "fra" to "Voici Text2MP3, qui parle sur votre appareil.",
            "spa" to "Esto es Text2MP3, hablando en tu dispositivo.",
            "ita" to "Questo è Text2MP3, che parla sul tuo dispositivo.",
            "por" to "Este é o Text2MP3, falando no seu dispositivo.",
            "nld" to "Dit is Text2MP3, dat op uw apparaat spreekt.",
            "rus" to "Это Text2MP3, говорящий на вашем устройстве."
        )
    }
}
