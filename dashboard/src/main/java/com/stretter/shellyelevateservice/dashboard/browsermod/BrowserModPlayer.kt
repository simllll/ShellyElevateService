package com.stretter.shellyelevateservice.dashboard.browsermod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.stretter.shellyelevateservice.dashboard.hass.HassClient
import com.stretter.shellyelevateservice.dashboard.json.EmptyJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * The audio side of browser_mod's `media_player.<browser>` ([PlayerCommand]s in, [state] out),
 * played with Android's [MediaPlayer] in the dashboard process, so it keeps working while the
 * activity is stopped (screen off/dimmed).
 *
 * - Volume is the device's media stream volume (`volume_level` 0.5 = half of `STREAM_MUSIC`), the
 *   same volume the service's MQTT volume control sets; `mute` silences only this player.
 * - `announce: true` (also what `tts.speak` sends) plays on a second player with
 *   `USAGE_ASSISTANT` and transient audio focus; media that was playing is paused and resumed
 *   afterwards. Plain `play_media` replaces the current media (`USAGE_MEDIA`, full focus).
 * - Video URLs play their audio track only (no video surface).
 *
 * Main thread only ([scope] must use the main dispatcher; MediaPlayer callbacks arrive there too).
 */
class BrowserModPlayer(
    context: Context,
    private val hass: HassClient,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Called after every change of [state] (BrowserMod reports it). */
    var onStateChanged: (() -> Unit)? = null

    /** HA's `internal_url`/`external_url` (from `get_config`), see [MediaUrls.resolve]. */
    var haUrls: List<String> = emptyList()

    private var main: MediaPlayer? = null
    private var mainSrc = ""
    private var mainExtra: JsonObject = EmptyJsonObject
    private var mainPrepared = false
    private var announcementPrepared = false
    /** The media should be playing (set before preparation finishes, like `audio.play()`). */
    private var mainWanted = false
    private var mainEnded = false
    private var stopped = false
    private var pausedByFocusLoss = false

    private var announcement: MediaPlayer? = null
    private var announcementSrc = ""
    private var announcementExtra: JsonObject = EmptyJsonObject
    private var resumeAfterAnnouncement = false

    private var muted = false
    private var ducked = false
    private var ticker: Job? = null

    private val mainFocus = Focus(AudioManager.AUDIOFOCUS_GAIN, mediaAttributes()) { onMainFocusChange(it) }
    private val announcementFocus = Focus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, announcementAttributes()) {}

    init {
        // Hardware keys, MQTT or another app changed the media volume: report the new level.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) changed()
            }
        }
        ContextCompat.registerReceiver(this.context, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    val state: PlayerState
        get() {
            val announcing = announcement != null
            val player = if (announcing) announcement else main
            // getDuration() before onPrepared puts MediaPlayer into its error state and aborts playback.
            val prepared = if (announcing) announcementPrepared else mainPrepared
            return PlayerState(
                state = when {
                    announcing -> "playing"
                    stopped || mainEnded -> "stopped"
                    mainSrc.isNotEmpty() -> if (mainWanted) "playing" else "paused"
                    else -> "off"
                },
                volume = streamVolume(),
                muted = muted,
                src = if (announcing) announcementSrc else mainSrc,
                durationS = player?.takeIf { prepared }?.safeDuration()?.let { it / 1000.0 },
                positionS = player?.takeIf { prepared }?.safePosition()?.let { it / 1000.0 } ?: 0.0,
                extra = if (announcing) announcementExtra else mainExtra,
            )
        }

    fun handle(command: PlayerCommand) {
        Log.i(TAG, "$command")
        when (command) {
            is PlayerCommand.Play -> if (command.url == null) resume() else play(command)
            PlayerCommand.Pause -> {
                cancelAnnouncement()
                pauseMain()
            }
            PlayerCommand.Stop -> clear(stopped = true)
            PlayerCommand.TurnOff -> clear(stopped = false)
            PlayerCommand.TurnOn -> Unit
            is PlayerCommand.SetVolume -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (command.level * max).roundToInt(), 0)
                } catch (e: SecurityException) {
                    Log.w(TAG, "setting the volume failed (do not disturb?): ${e.message}")
                }
            }
            is PlayerCommand.Mute -> {
                muted = command.mute ?: !muted
                applyVolume()
            }
            is PlayerCommand.Seek -> main?.takeIf { mainPrepared }?.let {
                try {
                    it.seekTo((command.positionS * 1000).toInt())
                    mainEnded = false
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "seek failed", e)
                }
            }
        }
        changed()
    }

    private fun play(command: PlayerCommand.Play) {
        val media = MediaUrls.resolve(command.url!!, hass.config?.url, haUrls)
        if (media == null) {
            Log.w(TAG, "cannot play ${command.url}")
            return
        }
        if (command.announce) {
            announce(media, command.url, command.extra)
            return
        }
        cancelAnnouncement()
        releaseMain()
        mainSrc = media.url
        mainExtra = command.extra
        mainWanted = true
        mainEnded = false
        stopped = false
        main = createPlayer(media, mediaAttributes(),
            onPrepared = {
                mainPrepared = true
                // During an announcement the media starts when it ends (finishAnnouncement).
                if (mainWanted && announcement == null) startMain()
                changed()
            },
            onDone = { error ->
                mainEnded = true
                mainWanted = false
                if (error) releaseMain()
                mainFocus.abandon()
                changed()
            },
        )
        if (main == null) {
            mainWanted = false
            stopped = true
        }
    }

    private fun resume() {
        cancelAnnouncement()
        val mp = main ?: return
        if (mainSrc.isEmpty()) return
        stopped = false
        mainWanted = true
        if (mainPrepared) {
            if (mainEnded) mp.seekTo(0)
            mainEnded = false
            startMain()
        }
    }

    private fun announce(media: ResolvedMedia, originalUrl: String, extra: JsonObject) {
        // A second announcement replaces the first; resume the media after the last one.
        if (announcement == null) {
            resumeAfterAnnouncement = mainWanted && !stopped
            main?.takeIf { mainPrepared && it.isPlayingSafe() }?.pause()
        }
        releaseAnnouncement()
        announcementSrc = media.url
        announcementExtra = extra.takeIf { it.isNotEmpty() } ?: EmptyJsonObject
        Log.i(TAG, "announcement $originalUrl")
        announcement = createPlayer(media, announcementAttributes(),
            onPrepared = { mp ->
                announcementPrepared = true
                announcementFocus.request()
                applyVolume()
                mp.start()
                changed()
            },
            onDone = { finishAnnouncement() },
        )
        if (announcement == null) finishAnnouncement()
    }

    private fun finishAnnouncement() {
        releaseAnnouncement()
        announcementFocus.abandon()
        if (resumeAfterAnnouncement && mainWanted && !stopped) {
            if (mainPrepared) startMain()
        }
        resumeAfterAnnouncement = false
        changed()
    }

    /** Stops an announcement without resuming the media. */
    private fun cancelAnnouncement() {
        if (announcement == null) return
        resumeAfterAnnouncement = false
        releaseAnnouncement()
        announcementFocus.abandon()
    }

    private fun startMain() {
        val mp = main ?: return
        mainFocus.request()
        applyVolume()
        try {
            mp.start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "start failed", e)
        }
        startTicker()
    }

    private fun pauseMain() {
        mainWanted = false
        pausedByFocusLoss = false
        main?.takeIf { mainPrepared && it.isPlayingSafe() }?.pause()
    }

    /** `player-stop` ([stopped] = state `stopped`) and `player-turn-off` (state `off`). */
    private fun clear(stopped: Boolean) {
        cancelAnnouncement()
        releaseMain()
        mainSrc = ""
        mainExtra = EmptyJsonObject
        mainWanted = false
        mainEnded = false
        this.stopped = stopped
        mainFocus.abandon()
    }

    private fun onMainFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                mainFocus.lost()
                pauseMain()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (main?.isPlayingSafe() == true) {
                main?.pause()
                pausedByFocusLoss = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                applyVolume()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                ducked = false
                applyVolume()
                if (pausedByFocusLoss && mainWanted && announcement == null) main?.start()
                pausedByFocusLoss = false
            }
        }
        changed()
    }

    private fun applyVolume() {
        val v = if (muted) 0f else 1f
        main?.setVolume(if (ducked) v * DUCK_VOLUME else v, if (ducked) v * DUCK_VOLUME else v)
        announcement?.setVolume(v, v)
    }

    private fun createPlayer(
        media: ResolvedMedia,
        attributes: AudioAttributes,
        onPrepared: (MediaPlayer) -> Unit,
        onDone: (error: Boolean) -> Unit,
    ): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attributes)
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            val headers = if (media.authorize) hass.config?.token?.let { mapOf("Authorization" to "Bearer $it") } else null
            mp.setDataSource(context, Uri.parse(media.url), headers.orEmpty())
            mp.setOnPreparedListener { if (it === main || it === announcement) onPrepared(it) }
            mp.setOnCompletionListener { if (it === main || it === announcement) onDone(false) }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "playback error $what/$extra: ${media.url.substringBefore('?')}")
                if (p === main || p === announcement) onDone(true)
                true
            }
            mp.prepareAsync()
            mp
        } catch (e: Exception) {
            Log.w(TAG, "cannot open ${media.url.substringBefore('?')}: ${e.message}")
            mp.release()
            null
        }
    }

    private fun releaseMain() {
        main?.release()
        main = null
        mainPrepared = false
        pausedByFocusLoss = false
    }

    private fun releaseAnnouncement() {
        announcement?.release()
        announcement = null
        announcementPrepared = false
        announcementSrc = ""
        announcementExtra = EmptyJsonObject
    }

    /** Position updates while playing (browser_mod.js throttles `timeupdate` the same way). */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(POSITION_INTERVAL_MS)
                if (main?.isPlayingSafe() != true && announcement == null) break
                changed()
            }
        }
    }

    private fun changed() = onStateChanged?.invoke()

    private fun streamVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 1f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /** Audio focus with the API 26 request object or the old listener API. */
    private inner class Focus(private val gain: Int, attributes: AudioAttributes, listener: (Int) -> Unit) {
        private val focusListener = AudioManager.OnAudioFocusChangeListener(listener)
        private val request: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(gain).setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build()
        } else null
        private var held = false

        /** Requests focus; plays anyway if denied (a wall panel has no player that must win). */
        fun request() {
            if (held) return
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audio.requestAudioFocus(request as AudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, gain)
            }
            held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!held) Log.w(TAG, "audio focus denied ($result)")
        }

        fun lost() {
            held = false
        }

        fun abandon() {
            if (!held) return
            held = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audio.abandonAudioFocusRequest(request as AudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(focusListener)
            }
        }
    }

    companion object {
        private const val TAG = "BrowserModPlayer"
        private const val POSITION_INTERVAL_MS = 10_000L
        private const val DUCK_VOLUME = 0.2f
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        private fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /** Announcements/TTS; USAGE_ASSISTANT (API 26+) also plays on the media stream. */
        private fun announcementAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        private fun MediaPlayer.isPlayingSafe(): Boolean = try { isPlaying } catch (_: IllegalStateException) { false }
        private fun MediaPlayer.safeDuration(): Int? = try { duration.takeIf { it > 0 } } catch (_: IllegalStateException) { null }
        private fun MediaPlayer.safePosition(): Int? = try { currentPosition } catch (_: IllegalStateException) { null }
    }
}
