package com.alimuhammad.text2mp3.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.alimuhammad.text2mp3.MainActivity
import com.alimuhammad.text2mp3.Piper
import com.alimuhammad.text2mp3.R
import com.alimuhammad.text2mp3.text.Sentences

class ReaderService : Service() {

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var worker: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var paused = false
    @Volatile private var jumpTo = -1
    private val pauseLock = Object()

    private var track: AudioTrack? = null
    private var trackRate = 0

    private var sleepRunnable: Runnable? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> doStop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> doPause()
            AudioManager.AUDIOFOCUS_GAIN -> if (paused) doResume()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        session = MediaSessionCompat(this, "PiperReader").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = doResume()
                override fun onPause() = doPause()
                override fun onStop() = doStop()
                override fun onSkipToNext() = doSkip(+1)
                override fun onSkipToPrevious() = doSkip(-1)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> startReading()
            ACTION_PAUSE -> doPause()
            ACTION_RESUME -> doResume()
            ACTION_TOGGLE -> if (paused) doResume() else doPause()
            ACTION_NEXT -> doSkip(+1)
            ACTION_PREV -> doSkip(-1)
            ACTION_STOP -> doStop()
            ACTION_SLEEP -> scheduleSleep(intent.getIntExtra(EXTRA_SLEEP_MIN, 0))
            else -> {  }
        }
        return START_NOT_STICKY
    }

    private fun startReading() {
        val text = ReaderState.pendingText ?: ReaderState.sourceText
        ReaderState.pendingText = null
        if (text.isBlank()) { doStop(); return }

        stopWorker()

        ReaderState.setStatus(ReaderState.Status.PREPARING)
        ReaderState.error = null
        val stem = ReaderState.voiceStem.ifBlank { runCatching { Piper.config.defaultVoice }.getOrDefault("") }
        ReaderState.voiceStem = stem
        ReaderState.sourceText = text
        ReaderState.spans = Sentences.split(text)
        if (ReaderState.spans.isEmpty()) { doStop(); return }

        if (!requestFocus()) { ReaderState.error = "Could not get audio focus."; doStop(); return }
        startForegroundCompat(buildNotification(playing = true))

        stopFlag = false
        paused = false
        jumpTo = -1
        val start = ReaderState.index.value.let { if (it in ReaderState.spans.indices) it else 0 }
        worker = Thread { runLoop(stem, start) }.also { it.isDaemon = true; it.start() }
    }

    private fun runLoop(stem: String, startIndex: Int) {
        var i = startIndex
        try {
            while (i < ReaderState.spans.size && !stopFlag) {
                synchronized(pauseLock) {
                    while (paused && !stopFlag) { runCatching { pauseLock.wait() } }
                }
                if (stopFlag) break
                if (jumpTo >= 0) { i = jumpTo.coerceIn(0, ReaderState.spans.size - 1); jumpTo = -1 }

                ReaderState.setIndex(i)
                ReaderState.setStatus(ReaderState.Status.PLAYING)
                main.post { updateNotification(playing = true) }

                val span = ReaderState.spans[i]
                val pcm = runCatching {
                    Piper.engine.synthesizeFloat(span.text, stem, pitch = Piper.config.pitch)
                }.getOrElse {
                    ReaderState.error = it.message; null
                }
                if (pcm == null) { i++; continue }

                val gain = runCatching { Piper.config.volume }.getOrDefault(1.0f)
                val shorts = Piper.engine.floatToShort(pcm.samples, gain)
                if (!playChunk(shorts, pcm.sampleRate)) {

                    if (stopFlag) break
                    if (jumpTo >= 0) continue
                }
                if (jumpTo < 0) i++
            }
            if (!stopFlag) {
                ReaderState.setStatus(ReaderState.Status.DONE)
                ReaderState.setIndex(-1)
            }
        } catch (e: Exception) {
            ReaderState.error = e.message
            ReaderState.setStatus(ReaderState.Status.ERROR)
        } finally {
            main.post { if (!stopFlag) stopSelfClean() }
        }
    }

    private fun playChunk(shorts: ShortArray, sampleRate: Int): Boolean {
        if (shorts.isEmpty()) return true
        ensureTrack(sampleRate)
        val t = track ?: return false
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }

        val slice = 4096
        var off = 0
        while (off < shorts.size) {
            if (stopFlag || jumpTo >= 0) return false
            synchronized(pauseLock) {
                while (paused && !stopFlag) {
                    runCatching { t.pause() }
                    runCatching { pauseLock.wait() }
                }
            }
            if (stopFlag || jumpTo >= 0) return false
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }
            val n = minOf(slice, shorts.size - off)
            val written = t.write(shorts, off, n)
            if (written < 0) return false
            off += written
        }
        return true
    }

    private fun ensureTrack(sampleRate: Int) {
        if (track != null && trackRate == sampleRate) return
        runCatching { track?.stop() }
        runCatching { track?.release() }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        trackRate = sampleRate
    }

    private fun doPause() {
        if (paused) return
        paused = true
        runCatching { track?.pause() }
        ReaderState.setStatus(ReaderState.Status.PAUSED)
        updateMediaState()
        updateNotification(playing = false)
    }

    private fun doResume() {
        if (!paused) return
        paused = false
        requestFocus()
        runCatching { track?.play() }
        synchronized(pauseLock) { pauseLock.notifyAll() }
        ReaderState.setStatus(ReaderState.Status.PLAYING)
        updateMediaState()
        updateNotification(playing = true)
    }

    private fun doSkip(delta: Int) {
        val cur = ReaderState.index.value.coerceAtLeast(0)
        jumpTo = (cur + delta).coerceIn(0, (ReaderState.spans.size - 1).coerceAtLeast(0))
        runCatching { track?.flush() }
        if (paused) doResume()
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    private fun doStop() {
        stopWorker()
        ReaderState.setStatus(ReaderState.Status.IDLE)
        ReaderState.setIndex(-1)
        stopSelfClean()
    }

    private fun stopWorker() {
        stopFlag = true
        synchronized(pauseLock) { pauseLock.notifyAll() }
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        worker?.let { runCatching { it.join(1500) } }
        worker = null
    }

    private fun stopSelfClean() {
        abandonFocus()
        cancelSleep()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        trackRate = 0
        runCatching { session.isActive = false }
        stopForegroundCompat()
        stopSelf()
    }

    private fun scheduleSleep(minutes: Int) {
        cancelSleep()
        if (minutes <= 0) return
        val r = Runnable { doStop() }
        sleepRunnable = r
        main.postDelayed(r, minutes * 60_000L)
    }

    private fun cancelSleep() {
        sleepRunnable?.let { main.removeCallbacks(it) }
        sleepRunnable = null
    }

    private var focusRequest: Any? = null

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (focusRequest as? android.media.AudioFocusRequest)?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioManager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Reader playback", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun action(act: String): PendingIntent {
        val i = Intent(this, ReaderService::class.java).setAction(act)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(this, act.hashCode(), i, flags)
    }

    private fun buildNotification(playing: Boolean): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val playPause = if (playing)
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", action(ACTION_PAUSE))
        else
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", action(ACTION_RESUME))

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_speak)
            .setContentTitle(ReaderState.title.ifBlank { "Text2MP3 reader" })
            .setContentText(progressText())
            .setContentIntent(contentPi)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREV))
            .addAction(playPause)
            .addAction(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun progressText(): String {
        val i = ReaderState.index.value
        val total = ReaderState.spans.size
        return if (i in 0 until total) "Sentence ${i + 1} of $total" else "Ready"
    }

    private fun updateNotification(playing: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(playing))
    }

    private fun updateMediaState() {
        val state = if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        updateMediaState()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopWorker()
        abandonFocus()
        cancelSleep()
        runCatching { session.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "piper_reader"
        const val NOTIF_ID = 4711

        const val ACTION_PLAY = "com.alimuhammad.text2mp3.PLAY"
        const val ACTION_PAUSE = "com.alimuhammad.text2mp3.PAUSE"
        const val ACTION_RESUME = "com.alimuhammad.text2mp3.RESUME"
        const val ACTION_TOGGLE = "com.alimuhammad.text2mp3.TOGGLE"
        const val ACTION_NEXT = "com.alimuhammad.text2mp3.NEXT"
        const val ACTION_PREV = "com.alimuhammad.text2mp3.PREV"
        const val ACTION_STOP = "com.alimuhammad.text2mp3.STOP"
        const val ACTION_SLEEP = "com.alimuhammad.text2mp3.SLEEP"
        const val EXTRA_SLEEP_MIN = "sleep_min"

        fun play(ctx: Context, text: String, voiceStem: String, title: String, startIndex: Int = 0) {
            ReaderState.pendingText = text
            ReaderState.voiceStem = voiceStem
            ReaderState.title = title
            ReaderState.setIndex(startIndex)
            val i = Intent(ctx, ReaderService::class.java).setAction(ACTION_PLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ReaderService::class.java).setAction(action)
            ctx.startService(i)
        }

        fun sleep(ctx: Context, minutes: Int) {
            val i = Intent(ctx, ReaderService::class.java).setAction(ACTION_SLEEP)
                .putExtra(EXTRA_SLEEP_MIN, minutes)
            ctx.startService(i)
        }
    }
}
