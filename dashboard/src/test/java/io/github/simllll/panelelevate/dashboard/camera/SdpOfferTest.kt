package io.github.simllll.panelelevate.dashboard.camera

import io.github.simllll.panelelevate.dashboard.cards.cameraEntityOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpOfferTest {
    // Shape of a libwebrtc receive-only offer (audio + video, many codecs), shortened.
    private val offer = listOf(
        "v=0",
        "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0 1",
        "a=extmap-allow-mixed",
        "a=msid-semantic: WMS",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8 13 110 126",
        "c=IN IP4 0.0.0.0",
        "a=rtcp:9 IN IP4 0.0.0.0",
        "a=ice-ufrag:abcd",
        "a=ice-pwd:0123456789abcdefghijklmn",
        "a=fingerprint:sha-256 AA:BB",
        "a=setup:actpass",
        "a=mid:0",
        "a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level",
        "a=recvonly",
        "a=rtcp-mux",
        "a=rtpmap:111 opus/48000/2",
        "a=rtcp-fb:111 transport-cc",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=rtpmap:63 red/48000/2",
        "a=fmtp:63 111/111",
        "a=rtpmap:9 G722/8000",
        "a=rtpmap:0 PCMU/8000",
        "a=rtpmap:8 PCMA/8000",
        "a=rtpmap:13 CN/8000",
        "a=rtpmap:110 telephone-event/48000",
        "a=rtpmap:126 telephone-event/8000",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 104 105 106 107 108 127 125 39",
        "c=IN IP4 0.0.0.0",
        "a=rtcp:9 IN IP4 0.0.0.0",
        "a=ice-ufrag:abcd",
        "a=ice-pwd:0123456789abcdefghijklmn",
        "a=fingerprint:sha-256 AA:BB",
        "a=setup:actpass",
        "a=mid:1",
        "a=extmap:14 urn:ietf:params:rtp-hdrext:toffset",
        "a=recvonly",
        "a=rtcp-mux",
        "a=rtcp-rsize",
        "a=rtcp-fb:* ccm fir",
        "a=rtpmap:96 VP8/90000",
        "a=rtcp-fb:96 goog-remb",
        "a=rtcp-fb:96 nack",
        "a=rtpmap:97 rtx/90000",
        "a=fmtp:97 apt=96",
        "a=rtpmap:102 H264/90000",
        "a=rtcp-fb:102 nack",
        "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f",
        "a=rtpmap:103 rtx/90000",
        "a=fmtp:103 apt=102",
        "a=rtpmap:104 H264/90000",
        "a=fmtp:104 level-asymmetry-allowed=1;packetization-mode=0;profile-level-id=42e01f",
        "a=rtpmap:105 rtx/90000",
        "a=fmtp:105 apt=104",
        "a=rtpmap:106 H264/90000",
        "a=rtcp-fb:106 goog-remb",
        "a=rtcp-fb:106 nack",
        "a=rtcp-fb:106 nack pli",
        "a=fmtp:106 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
        "a=rtpmap:107 rtx/90000",
        "a=fmtp:107 apt=106",
        "a=rtpmap:108 H264/90000",
        "a=fmtp:108 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c1f",
        "a=rtpmap:127 red/90000",
        "a=rtpmap:125 rtx/90000",
        "a=fmtp:125 apt=127",
        "a=rtpmap:39 ulpfec/90000",
        "",
    ).joinToString("\r\n")

    private val restricted = SdpOffer.restrict(offer)
    private val lines = restricted.split("\r\n")

    @Test
    fun `keeps constrained baseline H264 as payload type 109`() {
        assertTrue(lines.contains("m=video 9 UDP/TLS/RTP/SAVPF 109"))
        assertTrue(lines.contains("a=rtpmap:109 H264/90000"))
        assertTrue(lines.contains("a=fmtp:109 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f"))
        assertTrue(lines.contains("a=rtcp-fb:109 nack pli"))
        assertTrue(lines.contains("a=rtcp-fb:* ccm fir"))
        assertFalse(restricted.contains("VP8"))
        assertFalse(restricted.contains("rtx/"))
        assertFalse(restricted.contains("ulpfec"))
        assertFalse(restricted.contains("640c1f"))
        assertFalse(restricted.contains(":106"))
    }

    @Test
    fun `keeps only opus as 111`() {
        assertTrue(lines.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111"))
        assertTrue(lines.contains("a=fmtp:111 minptime=10;useinbandfec=1"))
        assertFalse(restricted.contains("G722"))
        assertFalse(restricted.contains("telephone-event"))
        assertFalse(restricted.contains("red/48000"))
    }

    @Test
    fun `keeps session and transport lines and stays small`() {
        listOf("v=0", "a=group:BUNDLE 0 1", "a=mid:0", "a=mid:1", "a=setup:actpass", "a=recvonly", "a=ice-ufrag:abcd").forEach {
            assertTrue("missing $it", lines.contains(it))
        }
        assertTrue(restricted.endsWith("\r\n"))
        assertFalse(restricted.contains("\r\n\r\n"))
        assertTrue(SdpOffer.sizeOf(restricted) < SdpOffer.sizeOf(offer))
        assertTrue(SdpOffer.sizeOf(restricted) < SdpOffer.MAX_OFFER_BYTES)
    }

    @Test
    fun `renumbers a differently numbered H264`() {
        val sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 98 99\r\na=mid:0\r\na=rtpmap:98 VP9/90000\r\na=rtpmap:99 H264/90000\r\na=fmtp:99 packetization-mode=1;profile-level-id=42e01f\r\n"
        assertEquals(
            "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 109\r\na=mid:0\r\na=rtpmap:109 H264/90000\r\na=fmtp:109 packetization-mode=1;profile-level-id=42e01f\r\n",
            SdpOffer.restrict(sdp),
        )
    }

    @Test
    fun `leaves sections without H264 alone`() {
        val sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000\r\n"
        assertEquals(sdp, SdpOffer.restrict(sdp))
    }

    @Test
    fun `H264 preference`() {
        val pm0 = SdpOffer.Codec("1", "H264", "packetization-mode=0;profile-level-id=42e01f")
        val high = SdpOffer.Codec("2", "H264", "packetization-mode=1;profile-level-id=640c1f")
        val baseline = SdpOffer.Codec("3", "H264", "packetization-mode=1;profile-level-id=42001f")
        val cb = SdpOffer.Codec("4", "H264", "profile-level-id=42e01f;packetization-mode=1")
        assertEquals(cb, SdpOffer.pickH264(listOf(pm0, high, baseline, cb)))
        assertEquals(baseline, SdpOffer.pickH264(listOf(pm0, high, baseline)))
        assertEquals(high, SdpOffer.pickH264(listOf(pm0, high)))
        assertEquals(pm0, SdpOffer.pickH264(listOf(pm0)))
        assertNull(SdpOffer.pickH264(listOf(SdpOffer.Codec("5", "VP8", null))))
    }

    @Test
    fun `mid of an m-line index`() {
        assertEquals("0", SdpOffer.midOf(restricted, 0))
        assertEquals("1", SdpOffer.midOf(restricted, 1))
        assertNull(SdpOffer.midOf(restricted, 2))
    }

    @Test
    fun `camera entity from refreshable-picture-card config`() {
        assertEquals("camera.door", cameraEntityOf("camera.door", null))
        assertEquals("camera.eingang_2", cameraEntityOf(null, "/api/camera_proxy/camera.eingang_2"))
        assertEquals("camera.x", cameraEntityOf("sensor.url", "http://ha:8123/api/camera_proxy/camera.x?token=abc"))
        assertNull(cameraEntityOf(null, "/local/snapshot.jpg"))
        assertNull(cameraEntityOf("image.door", null))
    }
}
