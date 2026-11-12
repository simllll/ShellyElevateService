package com.stretter.shellyelevateservice.dashboard.browsermod

import com.stretter.shellyelevateservice.dashboard.json.HaJson
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerProtocolTest {
    private fun command(json: String): PlayerCommand? {
        val e = BrowserModProtocol.parseEvent(HaJson.parseToJsonElement(json)) as BrowserModProtocol.Event.Command
        return PlayerCommand.parse(e.name, JsonObject(e.data - BrowserModProtocol.TARGET_KEYS))
    }

    @Test
    fun `tts speak arrives as announce with a signed relative url`() {
        // What BrowserModPlayer.async_play_media sends for tts.speak (media-source resolved).
        val c = command(
            """{"command":"player-play","media_content_id":"/api/tts_proxy/abc123.mp3?authSig=xyz",
               "media_type":"audio/mpeg","announce":true,
               "extra":{"media_content_id":"media-source://tts/google_translate?message=Hallo","media_content_type":"music"},
               "browser_id":["walldisplay Eingang"]}"""
        ) as PlayerCommand.Play
        assertEquals("/api/tts_proxy/abc123.mp3?authSig=xyz", c.url)
        assertEquals("audio/mpeg", c.mediaType)
        assertTrue(c.announce)
        assertEquals("music", c.extra.string("media_content_type"))
    }

    @Test
    fun `play without url resumes`() {
        val c = command("""{"command":"player-play"}""") as PlayerCommand.Play
        assertNull(c.url)
        assertFalse(c.announce)
    }

    @Test
    fun `volume mute seek and simple commands`() {
        assertEquals(PlayerCommand.SetVolume(0.15f), command("""{"command":"player-set-volume","volume_level":0.15}"""))
        assertEquals(PlayerCommand.SetVolume(1f), command("""{"command":"player-set-volume","volume_level":3}"""))
        assertNull(command("""{"command":"player-set-volume"}"""))
        assertEquals(PlayerCommand.Mute(true), command("""{"command":"player-mute","mute":true}"""))
        assertEquals(PlayerCommand.Mute(null), command("""{"command":"player-mute"}"""))
        assertEquals(PlayerCommand.Seek(12.5), command("""{"command":"player-seek","position":12.5}"""))
        assertEquals(PlayerCommand.Pause, command("""{"command":"player-pause"}"""))
        assertEquals(PlayerCommand.Stop, command("""{"command":"player-stop"}"""))
        assertEquals(PlayerCommand.TurnOff, command("""{"command":"player-turn-off"}"""))
        assertEquals(PlayerCommand.TurnOn, command("""{"command":"player-turn-on"}"""))
        assertNull(command("""{"command":"player-browse"}"""))
    }

    @Test
    fun `relative urls resolve against the dashboard url with auth`() {
        assertEquals(
            ResolvedMedia("http://10.0.0.20:8123/api/tts_proxy/abc.mp3?authSig=x", authorize = true),
            MediaUrls.resolve("/api/tts_proxy/abc.mp3?authSig=x", "http://10.0.0.20:8123/"),
        )
        assertEquals(
            ResolvedMedia("http://10.0.0.20:8123/local/ding.mp3", authorize = true),
            MediaUrls.resolve("local/ding.mp3", "http://10.0.0.20:8123"),
        )
        assertNull(MediaUrls.resolve("/api/tts_proxy/abc.mp3", null))
    }

    @Test
    fun `ha internal and external urls are moved to the dashboard url`() {
        val ha = listOf("http://homeassistant.local:8123", "https://ha.example.ts.net")
        assertEquals(
            ResolvedMedia("http://10.0.0.20:8123/api/tts_proxy/abc.mp3?authSig=x", authorize = true),
            MediaUrls.resolve("https://ha.example.ts.net/api/tts_proxy/abc.mp3?authSig=x", "http://10.0.0.20:8123", ha),
        )
        assertEquals(
            ResolvedMedia("http://10.0.0.20:8123/local/a.mp3", authorize = true),
            MediaUrls.resolve("http://homeassistant.local:8123/local/a.mp3", "http://10.0.0.20:8123", ha),
        )
        assertEquals(
            ResolvedMedia("http://10.0.0.20:8123/local/a.mp3", authorize = true),
            MediaUrls.resolve("http://10.0.0.20:8123/local/a.mp3", "http://10.0.0.20:8123", ha),
        )
    }

    @Test
    fun `foreign urls never get the token`() {
        assertEquals(
            ResolvedMedia("https://www.myinstants.com/media/sounds/ciaoooo.mp3", authorize = false),
            MediaUrls.resolve("https://www.myinstants.com/media/sounds/ciaoooo.mp3", "http://10.0.0.20:8123"),
        )
        assertNull(MediaUrls.resolve("media-source://tts/google_translate?message=x", "http://10.0.0.20:8123"))
        assertNull(MediaUrls.resolve("  ", "http://10.0.0.20:8123"))
    }

    @Test
    fun `state update matches browser_mod js`() {
        val data = PlayerState(
            state = "playing", volume = 0.5f, muted = false, src = "http://x/a.mp3",
            durationS = 3.2, positionS = 1.0, extra = jsonOf("media_content_id" to "a"),
        ).toUpdateData()
        val p = data.obj("player")!!
        assertEquals("playing", p.string("state"))
        assertEquals("0.5", p["volume"].toString())
        assertEquals("http://x/a.mp3", p.string("src"))
        assertEquals("3.2", p["media_duration"].toString())
        val extra = p["extra"]!!.jsonObject
        assertEquals("a", extra.string("media_content_id"))
        assertEquals("false", extra["audioInteractionRequired"].toString())
        assertEquals("true", extra["videoInteractionRequired"].toString())
        assertFalse(PlayerState().toUpdateData().obj("player")!!.containsKey("media_duration"))
    }
}
