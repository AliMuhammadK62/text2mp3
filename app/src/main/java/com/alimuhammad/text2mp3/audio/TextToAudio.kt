package com.alimuhammad.text2mp3.audio

import com.alimuhammad.text2mp3.PiperException
import com.alimuhammad.text2mp3.PiperTtsEngine
import com.alimuhammad.text2mp3.text.Sentences

object TextToAudio {

    fun interface Progress { fun report(done: Int, total: Int) }

    @Throws(PiperException::class)
    fun convert(
        engine: PiperTtsEngine,
        text: String,
        voiceStem: String,
        sink: AudioSink,
        progress: Progress? = null,
        isCancelled: () -> Boolean = { false }
    ): Int {
        val spans = Sentences.splitForSynthesis(text, maxChars = 600)
        if (spans.isEmpty()) throw PiperException("The text has no readable content.")
        try {
            for ((i, span) in spans.withIndex()) {
                if (isCancelled()) break
                val pcm = engine.synthesizeChunk(span.text, voiceStem)
                sink.write(pcm.samples, pcm.sampleRate)
                progress?.report(i + 1, spans.size)
            }
            sink.finish()
            return spans.size
        } catch (e: PiperException) {
            throw e
        } catch (e: Exception) {
            throw PiperException("Audio export failed: ${e.message}", e)
        } finally {
            sink.close()
        }
    }
}
