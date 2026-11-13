package com.stretter.shellyelevateservice.dashboard.camera

import com.stretter.shellyelevateservice.dashboard.json.HaJson
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcSignalingTest {
    private fun json(text: String) = HaJson.parseToJsonElement(text)

    @Test
    fun `offer and candidate messages match the HA schema`() {
        val offer = WebRtcSignaling.offerMessage("camera.door", "v=0\r\n")
        assertEquals("camera/webrtc/offer", offer["type"].asString())
        assertEquals("camera.door", offer["entity_id"].asString())
        assertEquals("v=0\r\n", offer["offer"].asString())

        val candidate = WebRtcSignaling.candidateMessage("camera.door", "01JSESSION", CandidateSpec("candidate:1 1 udp 1 10.0.0.5 5000 typ host", "0", 0))
        assertEquals("camera/webrtc/candidate", candidate["type"].asString())
        assertEquals("01JSESSION", candidate["session_id"].asString())
        val c = candidate["candidate"].asObject()!!
        assertEquals("candidate:1 1 udp 1 10.0.0.5 5000 typ host", c["candidate"].asString())
        assertEquals("0", c["sdpMid"].asString())
        assertEquals("0", c["sdpMLineIndex"].toString())

        // Missing mid/index are left out, not sent as null.
        val bare = WebRtcSignaling.candidateMessage("camera.door", "s", CandidateSpec("candidate:x"))["candidate"].asObject()!!
        assertFalse(bare.containsKey("sdpMid"))
        assertFalse(bare.containsKey("sdpMLineIndex"))
    }

    @Test
    fun `parses subscription events`() {
        assertEquals(SignalingEvent.Session("01JABC"), WebRtcSignaling.parseEvent(json("""{"type":"session","session_id":"01JABC"}""")))
        assertEquals(SignalingEvent.Answer("v=0"), WebRtcSignaling.parseEvent(json("""{"type":"answer","answer":"v=0"}""")))
        assertEquals(
            SignalingEvent.Candidate(CandidateSpec("candidate:2", null, 1)),
            WebRtcSignaling.parseEvent(json("""{"type":"candidate","candidate":{"candidate":"candidate:2","sdpMLineIndex":1}}""")),
        )
        assertEquals(
            SignalingEvent.Error("no_answer", "Device did not answer within 20s"),
            WebRtcSignaling.parseEvent(json("""{"type":"error","code":"no_answer","message":"Device did not answer within 20s"}""")),
        )
        assertNull(WebRtcSignaling.parseEvent(json("""{"type":"later_feature"}""")))
        assertNull(WebRtcSignaling.parseEvent(json("""{"type":"answer"}""")))
        assertNull(WebRtcSignaling.parseEvent(JsonNull))
    }

    @Test
    fun `parses client config`() {
        val result = json(
            """{"configuration":{"iceServers":[{"urls":"stun:stun.home-assistant.io:3478"},
               {"urls":["turn:turn.example:3478","turns:turn.example:5349"],"username":"u","credential":"p"},{"urls":[]}]},
               "dataChannel":"dc"}""",
        )
        assertEquals(
            listOf(
                IceServerSpec(listOf("stun:stun.home-assistant.io:3478")),
                IceServerSpec(listOf("turn:turn.example:3478", "turns:turn.example:5349"), "u", "p"),
            ),
            WebRtcSignaling.parseIceServers(result),
        )
        assertEquals("dc", WebRtcSignaling.parseDataChannel(result))
        assertEquals(emptyList<IceServerSpec>(), WebRtcSignaling.parseIceServers(json("""{"configuration":{}}""")))
    }

    @Test
    fun `web_rtc capability`() {
        assertTrue(WebRtcSignaling.supportsWebRtc(json("""{"frontend_stream_types":["web_rtc"]}""")))
        assertFalse(WebRtcSignaling.supportsWebRtc(json("""{"frontend_stream_types":["hls"]}""")))
        assertFalse(WebRtcSignaling.supportsWebRtc(null))
    }

    @Test
    fun `local candidates wait for the session id`() {
        val queue = LocalCandidateQueue()
        val a = CandidateSpec("a")
        val b = CandidateSpec("b")
        assertEquals(emptyList<CandidateSpec>(), queue.onCandidate(a))
        assertEquals(emptyList<CandidateSpec>(), queue.onCandidate(b))
        assertEquals(listOf(a, b), queue.onSession("s1"))
        assertEquals("s1", queue.sessionId)
        val c = CandidateSpec("c")
        assertEquals(listOf(c), queue.onCandidate(c))
    }

    @Test
    fun `remote candidates wait for the answer`() {
        val queue = RemoteCandidateQueue()
        val a = CandidateSpec("a")
        assertEquals(emptyList<CandidateSpec>(), queue.onCandidate(a))
        assertEquals(listOf(a), queue.onRemoteDescriptionSet())
        val b = CandidateSpec("b")
        assertEquals(listOf(b), queue.onCandidate(b))
        assertEquals(emptyList<CandidateSpec>(), queue.onRemoteDescriptionSet())
    }

    @Test
    fun `retry policy gives up after max attempts`() {
        val policy = RetryPolicy(maxAttempts = 3, delaysMs = listOf(5_000, 20_000))
        assertEquals(5_000L, policy.delayBefore(2))
        assertEquals(20_000L, policy.delayBefore(3))
        assertNull(policy.delayBefore(4))
        assertNull(policy.delayBefore(1))
        assertEquals(20_000L, RetryPolicy(maxAttempts = 5, delaysMs = listOf(5_000, 20_000)).delayBefore(5))
    }

    @Test
    fun `offers per camera are spaced`() {
        val throttle = OfferThrottle(minIntervalMs = 5_000)
        assertEquals(0L, throttle.waitBefore("camera.a", 1_000))
        throttle.onStarted("camera.a", 1_000)
        assertEquals(3_000L, throttle.waitBefore("camera.a", 3_000))
        assertEquals(0L, throttle.waitBefore("camera.b", 3_000))
        assertEquals(0L, throttle.waitBefore("camera.a", 7_000))
    }

    @Test
    fun `permanent errors`() {
        assertTrue(isPermanentWebRtcError("no_device"))
        assertTrue(isPermanentWebRtcError("unauthorized"))
        assertFalse(isPermanentWebRtcError("no_answer"))
        assertFalse(isPermanentWebRtcError("webrtc_offer_failed"))
    }

    @Test
    fun `aspect ratio parsing like the frontend`() {
        assertEquals(16f / 9f, parseAspectRatio("16:9")!!, 0.001f)
        assertEquals(4f / 3f, parseAspectRatio("4x3")!!, 0.001f)
        assertEquals(16f / 9f, parseAspectRatio("56.25%")!!, 0.001f)
        assertEquals(1.5f, parseAspectRatio("1.5")!!, 0.001f)
        assertNull(parseAspectRatio("wide"))
        assertNull(parseAspectRatio(null))
    }
}
