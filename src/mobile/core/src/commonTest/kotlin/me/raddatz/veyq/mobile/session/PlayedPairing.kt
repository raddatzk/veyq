package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.crypto.PairingMarks
import me.raddatz.veyq.mobile.qr.QrFrame
import me.raddatz.veyq.mobile.qr.QrMatrix

/**
 * The server's pairing relay as `PairingHandler` keeps it, played in memory for [DeviceLinkTest] and [SessionModelTest]:
 * a channel per handle; one answer, written once; a delegation of the channel's one account, once, after the answer; one
 * envelope, after it; the collect that hands the envelope over spends the channel; every dead channel `404` alike.
 */
class PlayedServer {
    class Channel {
        var answer: String? = null
        var envelope: String? = null
        var delegated: String? = null
        var spent = false
        var expired = false
    }

    val channels = LinkedHashMap<String, Channel>()
    var capable = true

    /** A `422 capability_disabled` from every verb (the capability switched off after the setup read). */
    var off = false
    val delegations = mutableListOf<String>()
    val seals = mutableListOf<String>()
    private var tokens = 0

    private fun live(handle: String): Channel {
        if (off) throw DeviceLink.Off()
        val c = channels[handle] ?: throw DeviceLink.Gone()
        if (c.spent || c.expired) throw DeviceLink.Gone()
        return c
    }

    fun expire(handle: String) {
        channels[handle]?.expired = true
    }

    /** One side's view of the relay, counting its own collects. */
    inner class Side(val name: String) : DeviceLink.Relay {
        var collects = 0
        var answers = 0
        var closed = false

        /** Collects made after this side saw an answer: what must stay 0 for the trusted device (it would spend the grant). */
        var collectsAfterAnswer = 0
        private var sawAnswer = false

        override suspend fun capable() = this@PlayedServer.capable

        override suspend fun open(handle: String) {
            if (off) throw DeviceLink.Off()
            channels.getOrPut(handle) { Channel() }
        }

        override suspend fun answer(handle: String, answer: String) {
            answers++
            val c = live(handle)
            if (c.answer != null) throw DeviceLink.Gone()
            c.answer = answer
        }

        override suspend fun collect(handle: String): DeviceLink.Collected {
            collects++
            if (sawAnswer) collectsAfterAnswer++
            val c = live(handle)
            if (c.answer != null) sawAnswer = true
            val envelope = c.envelope
            if (envelope != null) c.spent = true
            return DeviceLink.Collected(c.answer, envelope)
        }

        override fun close() {
            closed = true
        }
    }

    /** The trusted account's half over this server. */
    inner class Account(
        override val email: String = "p@example.org",
        private val key: ByteArray = ByteArray(32) { (it + 1).toByte() },
        private val marks: PairingMarks? = PairingMarks(3, 4, mapOf("scope-1" to 2L)),
    ) : DeviceLink.Granter {
        override val relay = Side("trusted")
        val handedKeys = mutableListOf<ByteArray>()

        override fun masterKey(): ByteArray = key.copyOf().also { handedKeys += it }
        override suspend fun marks() = marks

        override suspend fun delegate(handle: String): String {
            val c = live(handle)
            if (c.answer == null || c.delegated != null) throw DeviceLink.Gone()
            val token = "token-${++tokens}"
            c.delegated = token
            delegations += handle
            return token
        }

        override suspend fun seal(handle: String, envelope: String) {
            val c = live(handle)
            if (c.delegated == null || c.envelope != null) throw DeviceLink.Gone()
            c.envelope = envelope
            seals += handle
        }
    }
}

/** [m] drawn black on white, [scale] pixels a module, its quiet zone included: what a camera would hand the scan. */
fun frameOf(m: QrMatrix, scale: Int = 3): QrFrame {
    val side = m.side * scale
    return QrFrame(side, side, side, ByteArray(side * side) { if (m.dark((it % side) / scale, (it / side) / scale)) 0 else -1 })
}
