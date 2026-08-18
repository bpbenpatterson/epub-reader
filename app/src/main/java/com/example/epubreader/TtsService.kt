package com.example.epubreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

object BookHolder {
    var title: String = ""
    var chapters: List<Chapter> = emptyList()
}

data class BookProgress(
    val chapterTitle: String,
    val chapterIndex: Int,
    val totalChapters: Int
)

class TtsService : Service() {

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_LOAD = "com.example.epubreader.LOAD"
        private const val ACTION_SEEK = "com.example.epubreader.SEEK"
        private const val PREFS = "settings"
        private const val EXTRA_RESUME = "resume"

        fun load(context: Context, resume: Boolean = false) {
            val i = Intent(context, TtsService::class.java)
                .setAction(ACTION_LOAD)
                .putExtra(EXTRA_RESUME, resume)
            context.startForegroundService(i)
        }

        fun seekTo(context: Context, chapter: Int) {
            val i = Intent(context, TtsService::class.java)
                .setAction(ACTION_SEEK)
                .putExtra("chapter", chapter)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        fun getToken(): MediaSessionCompat.Token? = session?.sessionToken

        fun getRate(): Float = rate

        fun setRate(value: Float) {
            rate = value.coerceIn(0.5f, 2.0f)
            applyTtsSettings()
            saveSettings()
        }

        fun getChapterProgress(): Pair<Int, Int> = sentenceIdx to sentences.size

        fun getBookProgress(): BookProgress = BookProgress(
            chapterTitle = if (BookHolder.chapters.isEmpty()) "" else BookHolder.chapters[chapterIdx].title,
            chapterIndex = chapterIdx,
            totalChapters = BookHolder.chapters.size
        )

        fun seekWithinChapter(sentence: Int) {
            if (sentences.isEmpty()) return
            sentenceIdx = sentence.coerceIn(0, sentences.size - 1)
            savePosition(chapterIdx, sentenceIdx)
            if (playing) speakCurrent()
        }

        fun setSleepTimer(minutes: Int) = startSleepTimer(minutes)

        fun cancelSleepTimer() = clearSleepTimer()

        fun getSleepRemaining(): Long =
            if (sleepEndTime > 0) (sleepEndTime - SystemClock.elapsedRealtime()).coerceAtLeast(0) else 0L
    }

    private var tts: TextToSpeech? = null
    private var session: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ready = false
    private var playing = false
    private var pendingPlay = false
    private var chapterIdx = 0
    private var sentenceIdx = 0
    private var sentences: List<String> = emptyList()

    private var rate = 1.0f
    private lateinit var prefs: android.content.SharedPreferences
    private var sleepHandler: Handler? = null
    private var sleepRunnable: Runnable? = null
    private var sleepEndTime = 0L

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        rate = prefs.getFloat("rate", 1.0f)
        createChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "epubreader:tts").apply {
            setReferenceCounted(false)
        }

        session = MediaSessionCompat(this, "EpubReaderTts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopReading()
                override fun onSkipToNext() = nextSentence()
                override fun onSkipToPrevious() = prevSentence()
            })
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyTtsSettings()
                ready = true
                if (pendingPlay) {
                    pendingPlay = false
                    play()
                }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { advance() }
            override fun onError(utteranceId: String?) { pause() }
        })

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> {
                if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
                    loadChapter(prefs.getInt("chapter", 0), prefs.getInt("sentence", 0))
                } else {
                    savePosition(0, 0)
                    loadChapter(0, 0)
                }
                pendingPlay = true
                if (ready) {
                    pendingPlay = false
                    play()
                }
            }
            ACTION_SEEK -> {
                loadChapter(intent.getIntExtra("chapter", 0), 0)
                if (playing) speakCurrent()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        clearSleepTimer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    // ---- settings (rate / voice) ----

    private fun applyTtsSettings() {
        val t = tts ?: return
        // Use the system's preferred TTS engine and its default voice.
        t.setSpeechRate(rate)
    }

    private fun saveSettings() {
        prefs.edit()
            .putFloat("rate", rate)
            .apply()
    }

    private fun savePosition(ch: Int, sentence: Int) {
        prefs.edit()
            .putInt("chapter", ch)
            .putInt("sentence", sentence)
            .apply()
    }

    // ---- sleep timer ----

    private fun startSleepTimer(minutes: Int) {
        clearSleepTimer()
        if (minutes <= 0) return
        sleepEndTime = SystemClock.elapsedRealtime() + minutes * 60_000L
        val h = sleepHandler ?: Handler(Looper.getMainLooper()).also { sleepHandler = it }
        val r = object : Runnable {
            override fun run() {
                if (playing) pause()
                clearSleepTimer()
            }
        }
        sleepRunnable = r
        h.postDelayed(r, minutes * 60_000L)
    }

    private fun clearSleepTimer() {
        sleepRunnable?.let { sleepHandler?.removeCallbacks(it) }
        sleepRunnable = null
        sleepEndTime = 0L
    }

    // ---- reading logic ----

    private fun loadChapter(ch: Int, sentence: Int) {
        if (BookHolder.chapters.isEmpty()) return
        chapterIdx = ch.coerceIn(0, BookHolder.chapters.size - 1)
        sentences = splitIntoSentences(BookHolder.chapters[chapterIdx].text)
        sentenceIdx = if (sentences.isEmpty()) 0 else sentence.coerceIn(0, sentences.size - 1)
        savePosition(chapterIdx, sentenceIdx)
        updateMetadata()
    }

    private fun play() {
        if (!ready || BookHolder.chapters.isEmpty()) return
        if (sentences.isEmpty()) {
            nextChapter()
            return
        }
        playing = true
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
        speakCurrent()
        setState(PlaybackStateCompat.STATE_PLAYING)
        updateNotification()
    }

    private fun pause() {
        playing = false
        tts?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        setState(PlaybackStateCompat.STATE_PAUSED)
    }

    private fun stopReading() {
        playing = false
        tts?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        setState(PlaybackStateCompat.STATE_STOPPED)
        stopSelf()
    }

    private fun speakCurrent() {
        if (sentenceIdx < 0 || sentenceIdx >= sentences.size) return
        val bundle = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(sentences[sentenceIdx], TextToSpeech.QUEUE_FLUSH, bundle, "utt-$chapterIdx-$sentenceIdx")
    }

    private fun advance() {
        if (!playing) return
        sentenceIdx++
        if (sentenceIdx >= sentences.size) {
            if (chapterIdx + 1 < BookHolder.chapters.size) {
                loadChapter(chapterIdx + 1, 0)
                speakCurrent()
            } else {
                stopReading()
            }
            return
        }
        savePosition(chapterIdx, sentenceIdx)
        speakCurrent()
    }

    private fun nextChapter() {
        if (BookHolder.chapters.isEmpty()) return
        if (chapterIdx + 1 < BookHolder.chapters.size) {
            loadChapter(chapterIdx + 1, 0)
            if (playing) speakCurrent()
        }
    }

    private fun nextSentence() {
        if (sentences.isEmpty()) return
        if (sentenceIdx + 1 < sentences.size) {
            sentenceIdx++
            savePosition(chapterIdx, sentenceIdx)
            if (playing) speakCurrent()
        } else {
            nextChapter()
        }
    }

    private fun prevSentence() {
        if (sentences.isEmpty()) return
        if (sentenceIdx > 0) {
            sentenceIdx--
            savePosition(chapterIdx, sentenceIdx)
            if (playing) speakCurrent()
        } else if (chapterIdx - 1 >= 0) {
            // jump to the previous chapter's last sentence (loadChapter clamps to the end)
            loadChapter(chapterIdx - 1, Int.MAX_VALUE)
            if (playing) speakCurrent()
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        val result = ArrayList<String>()
        for (para in text.split("\n")) {
            val t = para.trim()
            if (t.isEmpty()) continue
            val parts = t.split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"'\u201c])"))
            for (p in parts) if (p.isNotBlank()) result.add(p.trim())
        }
        return result
    }

    // ---- media session / notification ----

    private fun setState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, 0, 1.0f)
                .build()
        )
    }

    private fun updateMetadata() {
        if (BookHolder.chapters.isEmpty()) return
        val chapterLabel = "Chapter ${chapterIdx + 1} of ${BookHolder.chapters.size}"
        session?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, BookHolder.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, chapterLabel)
                .build()
        )
        updateNotification()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Read Aloud", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Text-to-speech playback"
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    private fun mediaButton(action: Long): PendingIntent =
        MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chapterLabel = if (BookHolder.chapters.isEmpty()) {
            "Open a book"
        } else {
            "Chapter ${chapterIdx + 1} of ${BookHolder.chapters.size}"
        }
        val style = MediaStyle().setMediaSession(session?.sessionToken)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(BookHolder.title.ifEmpty { "EPUB Reader" })
            .setContentText(chapterLabel)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous, "Previous",
                mediaButton(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            )
            .addAction(
                android.R.drawable.ic_media_play, "Play/Pause",
                mediaButton(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_media_next, "Next",
                mediaButton(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            )
            .setStyle(style)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}
