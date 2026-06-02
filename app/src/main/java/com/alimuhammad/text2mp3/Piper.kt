package com.alimuhammad.text2mp3

import android.content.Context

object Piper {
    @Volatile private var initialised = false

    val isInitialised: Boolean get() = initialised

    lateinit var config: PiperConfig
        private set
    lateinit var engine: PiperTtsEngine
        private set
    lateinit var repository: VoiceRepository
        private set

    val logBuffer = StringBuilder()

    @Synchronized
    fun init(ctx: Context) {
        if (initialised) return
        val app = ctx.applicationContext
        config = PiperConfig.resolve(app)
        EspeakAssets.ensureInstalled(app, config.espeakDataDir)
        BundledVoices.ensureInstalled(app, config.voicesDir)
        repository = VoiceRepository(config.voicesDir)
        engine = PiperTtsEngine(config) { msg, level ->
            synchronized(logBuffer) {
                val prefix = when (level) {
                    PiperTtsEngine.Level.WARNING -> "⚠ "
                    PiperTtsEngine.Level.ERROR -> "✖ "
                    else -> "· "
                }
                logBuffer.append(prefix).append(msg).append('\n')
            }
        }
        initialised = true
    }
}
