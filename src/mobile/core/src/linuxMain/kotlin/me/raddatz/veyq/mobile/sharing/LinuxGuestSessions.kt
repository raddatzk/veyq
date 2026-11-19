package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.session.SecretService
import me.raddatz.veyq.mobile.session.SecretServiceFailure

/**
 * [GuestSessions] on Linux (ADR 0150 decision 3): a received link's guest session is an item of the Secret Service's
 * default collection, where the device keys are (`LinuxDeviceKeys`, the same schema `me.raddatz.veyq.DeviceKey` and the
 * same libsecret loaded at run time — no other dependency), told apart by its attribute `alias`: `guest/` and the name
 * [GuestNames.session] gives — a truncated hash of the link's origin and id, never the share's id —, labelled
 * "veyq guest session". `secret-tool search alias guest/<name>` finds it.
 *
 * Where no Secret Service answers (no libsecret, no service on the session bus, a locked collection) a write throws, which
 * the naming reports, a read finds nothing and a remove does nothing: the guest names itself anew on the next visit, as
 * the web's guest in a private window does. A session never goes to the device store (`DeviceStore` holds no secret).
 */
class LinuxGuestSessions internal constructor(
    private val service: SecretService?,
    /** Prefixed to every item's alias: empty for the app's sessions; a test's own, so its items never meet another's. */
    private val namespace: String,
) : GuestSessions {
    /** The app's guest sessions, in the default collection of the session's Secret Service. */
    constructor() : this(SecretService.system, "")

    override fun read(name: String): String? = service?.let { runCatching { it.lookup(alias(name)) }.getOrNull() }

    override fun write(name: String, value: String) {
        val s = service ?: throw GuestSessionNotKept("no libsecret")
        try {
            s.store(alias(name), LABEL, value)
        } catch (e: SecretServiceFailure) {
            throw GuestSessionNotKept(e.message ?: "refused")
        }
    }

    override fun remove(name: String) {
        service?.let { runCatching { it.clear(alias(name)) } }
    }

    private fun alias(name: String) = namespace + PREFIX + name

    internal companion object {
        const val PREFIX = "guest/"
        const val LABEL = "veyq guest session"
    }
}

/** A guest session the Secret Service did not keep: its reason (never the session's value). */
class GuestSessionNotKept(reason: String) : IllegalStateException("guest session not kept: $reason")
