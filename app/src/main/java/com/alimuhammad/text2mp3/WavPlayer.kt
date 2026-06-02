package com.alimuhammad.text2mp3

import android.media.MediaPlayer
import java.io.File

class WavPlayer {
    private var player: MediaPlayer? = null

    fun play(path: String, deleteAfter: Boolean, onError: (String) -> Unit = {}) {
        stop()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                if (deleteAfter) runCatching { File(path).delete() }
            }
            mp.setOnErrorListener { p, what, extra ->
                p.release()
                if (player === p) player = null
                onError("Playback error ($what/$extra)")
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            onError(e.message ?: "Playback failed")
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }
}
