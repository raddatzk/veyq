package me.raddatz.veyq.core.appshell

/**
 * The origins of the outside services a member's browser may call right now (ADR 0088 decision 7), for the policy the
 * shell serves: `connect` for what it fetches (the geocoder, the breached-password check), `img` for what it loads as
 * an image (the map tiles). The kernel asks; the capability slice answers (`ExternalServiceOrigins`), so the shell names
 * no register entry. [current] must not touch the database: the header is written on the event loop, so the answer is a
 * snapshot its owner recomputes after every write that can change it.
 */
interface ExternalOrigins {
    fun current(): ExternalOriginSet
}

/** The origins to append, each list deduplicated and in a stable order so a memo of the emitted policy holds. */
data class ExternalOriginSet(val connect: List<String>, val img: List<String>) {
    companion object {
        val NONE = ExternalOriginSet(emptyList(), emptyList())
    }
}
