package me.raddatz.veyq.mobile.android

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.VolumeShaper
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import me.raddatz.veyq.mobile.audio.Deck
import me.raddatz.veyq.mobile.audio.NowPlaying
import me.raddatz.veyq.mobile.audio.PlayerCommands
import me.raddatz.veyq.mobile.audio.SystemControls
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.words.Msg
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the music's lines in logcat are tagged (a pass reads its decks and controls there). */
internal const val MUSIC_LOG = "veyq-music"

/** How a music track sounds to the system: media, music — what audio focus and the volume keys answer to. */
private val musicAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/**
 * One of the core player's two decks on Android (ADR 0142 decision 2): the framework's `MediaPlayer` over core's
 * [RangeReader] ([ReaderSource], the same glue a Memory's video plays through), so a track is never written decrypted. The
 * core decides what plays, when and how loud; this deck plays the bytes and runs the volume ramps it is asked for as a
 * `VolumeShaper` (API 26+), which the system steps smoothly — the core never steps the volume itself.
 *
 * The player holds a partial wake lock while it plays (`setWakeMode`), so the music goes on with the screen off; the
 * process stays because [MusicService] is in the foreground while a queue is loaded (ADR 0142 decision 3). It requests no
 * audio focus of its own: focus is the queue's ([AndroidMusicControls]), never a deck's.
 */
internal class MediaDeck(private val context: Context, private val events: Deck.Events) : Deck {
    @Volatile private var player: MediaPlayer? = null
    private var shaper: VolumeShaper? = null
    private var prepared = false

    /** The volume the last ramp went to: a deck loaded after it starts there. */
    @Volatile private var volume = 1f

    override suspend fun load(reader: RangeReader, mime: String): Long = suspendCancellableCoroutine { c ->
        clear()
        val p = MediaPlayer()
        player = p
        prepared = false
        try {
            p.setAudioAttributes(musicAttributes)
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(ReaderSource(reader))
            p.setOnPreparedListener { mp ->
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                shape(mp, volume, 0)
                if (c.isActive) c.resume(mp.duration.toLong())
            }
            p.setOnErrorListener { mp, what, extra ->
                Log.i(MUSIC_LOG, "deck error $what/$extra ($mime, prepared=$prepared)")
                if (player !== mp) return@setOnErrorListener true
                if (!prepared) {
                    if (c.isActive) c.resumeWithException(java.io.IOException("not playable here: $what/$extra"))
                } else {
                    events.failed()
                }
                true
            }
            p.setOnCompletionListener { mp -> if (player === mp) events.ended() }
            p.prepareAsync()
        } catch (e: Exception) {
            clear()
            if (c.isActive) c.resumeWithException(e)
        }
        c.invokeOnCancellation { clear() }
    }

    override suspend fun play() {
        val p = player ?: throw IllegalStateException("nothing loaded")
        p.start()
    }

    override fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    override fun seek(positionMs: Long) {
        runCatching { player?.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST) }
    }

    override suspend fun positionMs(): Long = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0

    override fun ramp(to: Double, overMs: Long) {
        volume = to.toFloat().coerceIn(0f, 1f)
        val p = player ?: return
        if (prepared) shape(p, volume, overMs)
    }

    /** The volume moved to [to] over [overMs] from where it stands now (the shaper joins its own last value). */
    private fun shape(p: MediaPlayer, to: Float, overMs: Long) {
        val config = VolumeShaper.Configuration.Builder()
            .setDuration(overMs.coerceAtLeast(1))
            .setCurve(floatArrayOf(0f, 1f), floatArrayOf(to, to))
            .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
            .build()
        try {
            val s = shaper
            if (s == null) {
                shaper = p.createVolumeShaper(config).also { it.apply(VolumeShaper.Operation.PLAY) }
            } else {
                s.replace(config, VolumeShaper.Operation.PLAY, true)
            }
        } catch (e: Exception) {
            // a player that would not take a shaper still plays; it is set where it is to go
            Log.i(MUSIC_LOG, "volume shaper refused: $e")
            runCatching { p.setVolume(to, to) }
        }
    }

    override fun clear() {
        val p = player ?: return
        player = null
        prepared = false
        runCatching { shaper?.close() }
        shaper = null
        runCatching { p.reset() }
        runCatching { p.release() }
    }
}

/**
 * The system's media controls on Android (ADR 0142 decision 3), the core's [SystemControls]: a framework `MediaSession`
 * (title, the node's name, the length, where it stands, play/pause/next/previous/seek) — the lock screen, the quick settings'
 * player and a headset's or a Bluetooth device's buttons follow it — and its `MediaStyle` notification, held by
 * [MusicService] in the foreground while a queue is loaded, so the music plays on with the app left or the screen off.
 *
 * Audio focus is the queue's: asked when it starts playing (`AUDIOFOCUS_GAIN`); lost for good it pauses, lost for a while
 * (a call) it pauses and plays again when focus comes back, and a short sound the system ducks it under. Headphones taken
 * out (`ACTION_AUDIO_BECOMING_NOISY`) pause it. A lightbox video plays without asking for focus (`ReaderPlayer`), so its
 * start is no loss here: the core interrupts the music for it (ADR 0142 decision 13) and its end resumes it.
 *
 * Everything runs on the main thread: the core calls from its own.
 */
internal class AndroidMusicControls(private val app: Application) : SystemControls {
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private var session: MediaSession? = null
    private var commands: PlayerCommands? = null
    private var shown: NowPlaying? = null

    /** What the notification last showed: it is posted anew only when one of these changes, not each second. */
    private var posted: Triple<String, String?, Boolean>? = null
    private var focused = false
    private var pausedForFocus = false
    private var noisyHeard = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(musicAttributes)
        .setOnAudioFocusChangeListener({ change -> focusChanged(change) }, main)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(MUSIC_LOG, "becoming noisy: paused")
                commands?.pause()
            }
        }
    }

    override fun bind(commands: PlayerCommands) {
        main.post {
            this.commands = commands
            MusicService.commands = commands
            val s = session ?: MediaSession(app, "veyq-music").also { session = it }
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = commands.resume()
                override fun onPause() = commands.pause()
                override fun onSkipToNext() = commands.next()
                override fun onSkipToPrevious() = commands.previous()
                override fun onSeekTo(pos: Long) = commands.seek(pos)
            }, main)
            s.setSessionActivity(openApp())
            s.isActive = true
        }
    }

    override fun show(now: NowPlaying) {
        main.post { showNow(now) }
    }

    private fun showNow(now: NowPlaying) {
        val s = session ?: return
        val before = shown
        shown = now
        if (before?.trackId != now.trackId || before.title != now.title || before.album != now.album || before.durationMs != now.durationMs) {
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, now.title)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, now.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, now.durationMs)
                    .build(),
            )
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO,
                )
                .setState(if (now.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, now.positionMs, if (now.playing) 1f else 0f)
                .build(),
        )
        if (now.playing) {
            focus()
            hearNoisy(true)
        } else {
            hearNoisy(false)
        }
        val look = Triple(now.title, now.album, now.playing)
        if (look != posted) {
            posted = look
            MusicService.show(app, notification(s, now))
        }
    }

    override fun clear() {
        main.post {
            commands = null
            MusicService.commands = null
            shown = null
            posted = null
            hearNoisy(false)
            if (focused) audio.abandonAudioFocusRequest(focusRequest)
            focused = false
            pausedForFocus = false
            session?.run {
                isActive = false
                release()
            }
            session = null
            MusicService.stop(app)
        }
    }

    private fun focus() {
        if (focused) return
        val answer = audio.requestAudioFocus(focusRequest)
        Log.i(MUSIC_LOG, "audio focus asked: $answer")
        if (answer == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focused = true else commands?.pause()
    }

    private fun focusChanged(change: Int) {
        Log.i(MUSIC_LOG, "audio focus changed: $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focused = false
                pausedForFocus = false
                audio.abandonAudioFocusRequest(focusRequest)
                commands?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (shown?.playing == true) {
                pausedForFocus = true
                commands?.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) {
                pausedForFocus = false
                commands?.resume()
            }
            // a short sound: the system ducks the music under it (USAGE_MEDIA, not paused on a duck)
            else -> Unit
        }
    }

    private fun hearNoisy(on: Boolean) {
        if (on == noisyHeard) return
        noisyHeard = on
        if (on) app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else runCatching { app.unregisterReceiver(noisy) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(icon: Int, label: String, what: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(app, icon), label,
            PendingIntent.getService(app, code, Intent(app, MusicService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE),
        ).build()

    /** The notification: title, the node's name, previous / play-pause / next, bound to the session (Material's media style). */
    private fun notification(s: MediaSession, now: NowPlaying): Notification {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MusicService.CHANNEL, app.words(Msg.MUSIC_NOTIFICATION_CHANNEL), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(app, MusicService.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(now.title)
            .setContentText(now.album)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(android.R.drawable.ic_media_previous, app.words(Msg.MUSIC_PREVIOUS), MusicService.PREVIOUS, 1))
            .addAction(
                if (now.playing) action(android.R.drawable.ic_media_pause, app.words(Msg.MUSIC_PAUSE), MusicService.PAUSE, 2)
                else action(android.R.drawable.ic_media_play, app.words(Msg.MUSIC_RESUME), MusicService.PLAY, 3),
            )
            .addAction(action(android.R.drawable.ic_media_next, app.words(Msg.MUSIC_NEXT), MusicService.NEXT, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}

/**
 * The music's foreground service (`foregroundServiceType="mediaPlayback"`, ADR 0142 decision 3): in the foreground with
 * the media notification from the first track shown — the press that played it, the app in front — until the queue
 * stops, so the process, its reader and the core's player go on with the app left or the screen off. It decides nothing:
 * the notification's buttons reach the core's player as the media session's do.
 */
class MusicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PREVIOUS -> commands?.previous()
            PLAY -> commands?.resume()
            PAUSE -> commands?.pause()
            NEXT -> commands?.next()
        }
        val n = shown
        if (n == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Log.i(MUSIC_LOG, "not in the foreground: $e")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "music"
        private const val ID = 7_102
        const val PREVIOUS = "me.raddatz.veyq.music.PREVIOUS"
        const val PLAY = "me.raddatz.veyq.music.PLAY"
        const val PAUSE = "me.raddatz.veyq.music.PAUSE"
        const val NEXT = "me.raddatz.veyq.music.NEXT"

        @Volatile internal var commands: PlayerCommands? = null
        @Volatile private var shown: Notification? = null
        @Volatile private var started = false

        /** [n] shown: the service started into the foreground the first time, the notification replaced after. */
        internal fun show(context: Context, n: Notification) {
            shown = n
            if (started) {
                context.getSystemService(NotificationManager::class.java).notify(ID, n)
                return
            }
            try {
                context.startForegroundService(Intent(context, MusicService::class.java))
                started = true
            } catch (e: Exception) {
                // started where the app is not in front (none of the core's paths does): it plays while the app is shown
                Log.i(MUSIC_LOG, "music service not started: $e")
            }
        }

        internal fun stop(context: Context) {
            shown = null
            started = false
            context.stopService(Intent(context, MusicService::class.java))
        }
    }
}
