package com.stretter.shellyelevateservice.dashboard.camera

/**
 * Shrinks the local receive-only offer to one H264 profile and Opus before it is set and sent.
 *
 * - BTicino intercoms (custom integration `bticino_intercom`) drop off the cloud and reboot
 *   when the offer exceeds ~8 KB (issues #74/#105); a full libwebrtc offer with every codec,
 *   rtx, red and fec is close to that. One codec per section keeps it around 2 KB.
 * - The same firmware sends H264 as payload type 109 and Opus as 111 whatever the SDP says
 *   (Chrome's numbers), so the kept codecs are renumbered to those. Other cameras answer with
 *   the payload types of the offer, so the numbers do not matter to them.
 *
 * Sections without H264/Opus stay as they are.
 */
object SdpOffer {
    /** Offers above this size are never sent (the BTicino limit is ~8 KB, keep a margin). */
    const val MAX_OFFER_BYTES = 6000

    const val H264_PAYLOAD_TYPE = 109
    const val OPUS_PAYLOAD_TYPE = 111

    fun restrict(sdp: String): String {
        val (session, sections) = split(sdp)
        val out = ArrayList<String>(session)
        for (section in sections) {
            out += when (section.first().substringBefore(' ')) {
                "m=video" -> keepSingleCodec(section, H264_PAYLOAD_TYPE) { pickH264(it) }
                "m=audio" -> keepSingleCodec(section, OPUS_PAYLOAD_TYPE) { codecs -> codecs.firstOrNull { it.name.equals("opus", true) } }
                else -> section
            }
        }
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    /** `a=mid` of the m-section at [index], for remote candidates that only carry `sdpMLineIndex`. */
    fun midOf(sdp: String, index: Int): String? =
        split(sdp).second.getOrNull(index)?.firstOrNull { it.startsWith("a=mid:") }?.substringAfter("a=mid:")?.trim()

    /** Number of bytes HA (and the camera) will receive. */
    fun sizeOf(sdp: String): Int = sdp.toByteArray(Charsets.UTF_8).size

    internal data class Codec(val pt: String, val name: String, val fmtp: String?)

    private fun split(sdp: String): Pair<List<String>, List<List<String>>> {
        val lines = sdp.split("\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        val session = ArrayList<String>()
        val sections = ArrayList<ArrayList<String>>()
        for (line in lines) {
            if (line.startsWith("m=")) sections += arrayListOf(line)
            else if (sections.isEmpty()) session += line
            else sections.last() += line
        }
        return session to sections
    }

    private fun codecs(section: List<String>): List<Codec> {
        val fmtp = section.filter { it.startsWith("a=fmtp:") }
            .associate { it.substringAfter("a=fmtp:").substringBefore(' ') to it.substringAfter(' ', "") }
        return section.filter { it.startsWith("a=rtpmap:") }.map { line ->
            val pt = line.substringAfter("a=rtpmap:").substringBefore(' ')
            val name = line.substringAfter(' ').substringBefore('/')
            Codec(pt, name, fmtp[pt])
        }
    }

    /**
     * Constrained baseline with packetization-mode 1 (`42e01f`, what Chrome puts on 109) first,
     * then any baseline-like profile with packetization-mode 1, then any H264.
     */
    internal fun pickH264(codecs: List<Codec>): Codec? {
        val h264 = codecs.filter { it.name.equals("H264", true) }
        fun Codec.param(key: String) = fmtp?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$key=", true) }?.substringAfter('=')?.lowercase()
        return h264.firstOrNull { it.param("packetization-mode") == "1" && it.param("profile-level-id")?.startsWith("42e0") == true }
            ?: h264.firstOrNull { it.param("packetization-mode") == "1" && it.param("profile-level-id")?.startsWith("42") == true }
            ?: h264.firstOrNull { it.param("packetization-mode") == "1" }
            ?: h264.firstOrNull()
    }

    private fun keepSingleCodec(section: List<String>, newPt: Int, pick: (List<Codec>) -> Codec?): List<String> {
        val keep = pick(codecs(section)) ?: return section
        val target = newPt.toString()
        val header = section.first().split(' ')
        if (header.size < 4) return section
        val out = ArrayList<String>(section.size)
        out += (header.take(3) + target).joinToString(" ")
        for (line in section.drop(1)) {
            val pt = payloadTypeOf(line)
            when {
                pt == null || pt == "*" -> out += line
                pt == keep.pt -> out += line.replaceFirst(":$pt", ":$target")
                // every other codec (rtx, red, fec, other profiles) and its parameters go
            }
        }
        return out
    }

    private fun payloadTypeOf(line: String): String? {
        for (prefix in PT_PREFIXES) {
            if (line.startsWith(prefix)) return line.substring(prefix.length).substringBefore(' ')
        }
        return null
    }

    private val PT_PREFIXES = listOf("a=rtpmap:", "a=fmtp:", "a=rtcp-fb:")
}
