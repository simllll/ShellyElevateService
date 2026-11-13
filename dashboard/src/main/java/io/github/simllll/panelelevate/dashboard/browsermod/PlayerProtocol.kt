package io.github.simllll.panelelevate.dashboard.browsermod

import io.github.simllll.panelelevate.dashboard.json.EmptyJsonObject
import io.github.simllll.panelelevate.dashboard.json.asBoolean
import io.github.simllll.panelelevate.dashboard.json.asDouble
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.obj
import io.github.simllll.panelelevate.dashboard.json.string
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The media player part of the browser_mod protocol (`media_player.py` and the `player-*`
 * listeners of `browser_mod.js`, 3.4.2).
 *
 * HA's `media_player.<browser>` sends `player-play {media_content_id, media_type, announce?,
 * enqueue?, extra}` (media-source ids are already resolved to a URL, usually relative and signed,
 * e.g. `/api/tts_proxy/<token>.mp3`), `player-play` without a URL (resume), `player-pause`,
 * `player-stop`, `player-set-volume {volume_level}`, `player-mute {mute?}` (no `mute` = toggle),
 * `player-seek {position}` (seconds), `player-turn-off` and `player-turn-on`.
 * The browser answers with `browser_mod/update {player: {…}}` ([PlayerState.toUpdateData]).
 */
sealed interface PlayerCommand {
    /** [url] null = resume the current media. */
    data class Play(val url: String?, val mediaType: String?, val announce: Boolean, val extra: JsonObject) : PlayerCommand
    data object Pause : PlayerCommand
    data object Stop : PlayerCommand
    data class SetVolume(val level: Float) : PlayerCommand

    /** [mute] null = toggle. */
    data class Mute(val mute: Boolean?) : PlayerCommand
    data class Seek(val positionS: Double) : PlayerCommand
    data object TurnOff : PlayerCommand
    data object TurnOn : PlayerCommand

    companion object {
        const val PREFIX = "player-"

        /** Parses a browser_mod command ([name] without `browser_mod.`); null if it is not a valid player command. */
        fun parse(name: String, data: JsonObject): PlayerCommand? = when (name) {
            "player-play" -> Play(
                url = data.string("media_content_id")?.takeIf { it.isNotBlank() },
                mediaType = data.string("media_type"),
                announce = data["announce"].asBoolean() == true,
                extra = data.obj("extra") ?: EmptyJsonObject,
            )
            "player-pause" -> Pause
            "player-stop" -> Stop
            "player-set-volume" -> data["volume_level"].asDouble()?.let { SetVolume(it.toFloat().coerceIn(0f, 1f)) }
            "player-mute" -> Mute(data["mute"].asBoolean())
            "player-seek" -> data["position"].asDouble()?.let { Seek(it.coerceAtLeast(0.0)) }
            "player-turn-off" -> TurnOff
            "player-turn-on" -> TurnOn
            else -> null
        }
    }
}

/** A media URL ready for Android's MediaPlayer; [authorize] = send the HA bearer token. */
data class ResolvedMedia(val url: String, val authorize: Boolean)

object MediaUrls {
    /**
     * Resolves a `player-play` URL like the browser would against the dashboard origin:
     * relative paths (`/api/tts_proxy/…`, `/local/…`, signed `?authSig=` paths) go to [baseUrl].
     * Absolute URLs pointing to HA's own `internal_url`/`external_url` ([haUrls]; HA prefixes
     * them when processing play URLs) are moved to [baseUrl] too, because the panel may not be able
     * to reach e.g. the external (Tailscale) address. Requests to HA get the bearer token, which
     * covers unsigned `/api/…` paths; other hosts never see it.
     * Null for URLs MediaPlayer cannot open (unresolved `media-source://`, unknown schemes).
     */
    fun resolve(url: String, baseUrl: String?, haUrls: Collection<String> = emptyList()): ResolvedMedia? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val base = baseUrl?.trim()?.trimEnd('/')?.toHttpUrlOrNull()
        val absolute = trimmed.toHttpUrlOrNull()
        if (absolute == null) {
            if (trimmed.contains("://")) return null
            base ?: return null
            val resolved = base.resolve(if (trimmed.startsWith("/")) trimmed else "/$trimmed") ?: return null
            return ResolvedMedia(resolved.toString(), authorize = true)
        }
        if (base == null) return ResolvedMedia(absolute.toString(), authorize = false)
        if (sameOrigin(absolute, base)) return ResolvedMedia(absolute.toString(), authorize = true)
        val ha = haUrls.mapNotNull { it.trim().toHttpUrlOrNull() }
        if (ha.any { sameOrigin(absolute, it) }) {
            val moved = absolute.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
            return ResolvedMedia(moved.toString(), authorize = true)
        }
        return ResolvedMedia(absolute.toString(), authorize = false)
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
}

/**
 * What the browser reports as `player` (`_player_update` in browser_mod.js). [state] is
 * `playing`, `paused`, `stopped` (after stop or at the end), `off` (no media) or `unavailable`.
 * Durations and positions are in seconds; an unknown duration is left out (JS sends `NaN` → null).
 */
data class PlayerState(
    val state: String = "off",
    val volume: Float = 1f,
    val muted: Boolean = false,
    val src: String = "",
    val durationS: Double? = null,
    val positionS: Double = 0.0,
    val extra: JsonObject = EmptyJsonObject,
    /** Video is played as audio only; HA warns that interaction is required. */
    val videoSupported: Boolean = false,
) {
    fun toUpdateData(): JsonObject = jsonOf(
        "player" to jsonOf(
            "volume" to volume.toDouble(),
            "muted" to muted,
            "src" to src,
            "state" to state,
            "media_duration" to durationS,
            "media_position" to positionS,
            "extra" to JsonObject(
                extra + jsonOf(
                    "videoInteractionRequired" to !videoSupported,
                    "audioInteractionRequired" to false,
                )
            ),
        )
    )
}
