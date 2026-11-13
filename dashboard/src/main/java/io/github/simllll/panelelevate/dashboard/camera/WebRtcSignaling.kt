package io.github.simllll.panelelevate.dashboard.camera

import io.github.simllll.panelelevate.dashboard.json.asArray
import io.github.simllll.panelelevate.dashboard.json.asInt
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.asStringList
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** An ICE server from `camera/webrtc/get_client_config` (`RTCIceServer`). */
data class IceServerSpec(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** An ICE candidate as HA sends and expects it (`RTCIceCandidateInit`). */
data class CandidateSpec(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int? = null)

/** An event of the `camera/webrtc/offer` subscription (HA `WebRTCMessage.as_dict()`). */
sealed interface SignalingEvent {
    data class Session(val sessionId: String) : SignalingEvent
    data class Answer(val sdp: String) : SignalingEvent
    data class Candidate(val candidate: CandidateSpec) : SignalingEvent
    data class Error(val code: String, val message: String) : SignalingEvent
}

/**
 * Messages of HA's camera WebRTC WebSocket API (`homeassistant/components/camera/webrtc.py`,
 * core 2026.11):
 *
 * - `camera/webrtc/get_client_config {entity_id}` → `{configuration: {iceServers: […]}, dataChannel?}`
 * - `camera/webrtc/offer {entity_id, offer}`: a subscription; events `{type: session, session_id}`,
 *   `{type: answer, answer}`, `{type: candidate, candidate: {candidate, sdpMid?, sdpMLineIndex?}}`,
 *   `{type: error, code, message}`. Unsubscribing closes the session on the camera.
 * - `camera/webrtc/candidate {entity_id, session_id, candidate}` for local candidates.
 */
object WebRtcSignaling {
    fun clientConfigMessage(entityId: String): JsonObject =
        jsonOf("type" to "camera/webrtc/get_client_config", "entity_id" to entityId)

    fun offerMessage(entityId: String, offerSdp: String): JsonObject =
        jsonOf("type" to "camera/webrtc/offer", "entity_id" to entityId, "offer" to offerSdp)

    fun candidateMessage(entityId: String, sessionId: String, candidate: CandidateSpec): JsonObject =
        jsonOf(
            "type" to "camera/webrtc/candidate",
            "entity_id" to entityId,
            "session_id" to sessionId,
            "candidate" to jsonOf(
                "candidate" to candidate.candidate,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
            ),
        )

    fun capabilitiesMessage(entityId: String): JsonObject =
        jsonOf("type" to "camera/capabilities", "entity_id" to entityId)

    /** `frontend_stream_types` of a `camera/capabilities` result contains `web_rtc`. */
    fun supportsWebRtc(capabilities: JsonElement?): Boolean =
        capabilities.asObject()?.get("frontend_stream_types").asStringList().contains("web_rtc")

    /** ICE servers of a `get_client_config` result; `urls` may be a string or a list. */
    fun parseIceServers(result: JsonElement?): List<IceServerSpec> {
        val servers = result.asObject()?.get("configuration").asObject()?.get("iceServers").asArray() ?: return emptyList()
        return servers.mapNotNull { element ->
            val server = element.asObject() ?: return@mapNotNull null
            val urls = server["urls"].asStringList().filter { it.isNotBlank() }
            if (urls.isEmpty()) null
            else IceServerSpec(urls, server["username"].asString(), server["credential"].asString())
        }
    }

    /** `dataChannel` of a `get_client_config` result (some cameras need one to stream). */
    fun parseDataChannel(result: JsonElement?): String? = result.asObject()?.get("dataChannel").asString()

    fun parseEvent(event: JsonElement?): SignalingEvent? {
        val obj = event.asObject() ?: return null
        return when (obj["type"].asString()) {
            "session" -> obj["session_id"].asString()?.let { SignalingEvent.Session(it) }
            "answer" -> obj["answer"].asString()?.let { SignalingEvent.Answer(it) }
            "candidate" -> {
                val c = obj["candidate"].asObject() ?: return null
                val candidate = c["candidate"].asString() ?: return null
                SignalingEvent.Candidate(CandidateSpec(candidate, c["sdpMid"].asString(), c["sdpMLineIndex"].asInt()))
            }
            "error" -> SignalingEvent.Error(obj["code"].asString() ?: "unknown", obj["message"].asString() ?: "")
            else -> null
        }
    }
}

/**
 * Local candidates are trickled with the `session_id`, which only arrives with the first
 * subscription event: candidates found before that wait here (like `_candidatesList` of
 * `ha-web-rtc-player`).
 */
class LocalCandidateQueue {
    private val waiting = ArrayList<CandidateSpec>()
    var sessionId: String? = null
        private set

    /** Returns the candidates to send now (all buffered ones once the session is known). */
    @Synchronized
    fun onCandidate(candidate: CandidateSpec): List<CandidateSpec> {
        if (sessionId == null) {
            waiting += candidate
            return emptyList()
        }
        return listOf(candidate)
    }

    @Synchronized
    fun onSession(id: String): List<CandidateSpec> {
        sessionId = id
        val flushed = waiting.toList()
        waiting.clear()
        return flushed
    }
}

/**
 * Remote candidates may arrive before the answer has been applied; libwebrtc rejects them
 * until the remote description is set, so they wait here.
 */
class RemoteCandidateQueue {
    private val waiting = ArrayList<CandidateSpec>()
    private var ready = false

    @Synchronized
    fun onCandidate(candidate: CandidateSpec): List<CandidateSpec> {
        if (!ready) {
            waiting += candidate
            return emptyList()
        }
        return listOf(candidate)
    }

    @Synchronized
    fun onRemoteDescriptionSet(): List<CandidateSpec> {
        ready = true
        val flushed = waiting.toList()
        waiting.clear()
        return flushed
    }
}
