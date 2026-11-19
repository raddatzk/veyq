package me.raddatz.veyq.mobile.sharing

/**
 * The screen a `veyq:` link opens (ADR 0150 § O1): **`veyq://link`** opens *Open a link* — on the sign-in or the unlock
 * screen, and signed in over the open tenant (owner 2026-11-19) —; **`veyq://open`** and every other input open the app
 * where it is.
 *
 * A custom scheme is owned by no one: any app on the device may claim `veyq:` and receive what a page sends there, and the
 * link reaches the browser's history and, on the desktops, the handler's command line. So the link carries nothing, and
 * [parse] reads nothing of it but its host: a path, a query, a fragment, user info and a port are ignored — never stored,
 * never logged, never handed on. The answer is one of two objects with no field, so nothing of the input can ride it; a
 * crafted `veyq://link#k=…` or `veyq://link?u=https://…` does exactly what `veyq://link` does.
 */
sealed interface DeepLink {
    /** The app opened where it is (`veyq://open`, and anything that is not `veyq://link`). */
    data object Open : DeepLink

    /** *Open a link* (`veyq://link`): the person pastes the link they copied there. */
    data object Link : DeepLink

    companion object {
        private const val SCHEME = "veyq"
        private const val LINK_HOST = "link"

        /** What [uri] opens: [Link] for a `veyq:` URI whose host is `link` (case aside), [Open] for anything else. */
        fun parse(uri: String): DeepLink = if (hostOf(uri) == LINK_HOST) Link else Open

        /**
         * The host of a `veyq://` URI, lowercased; null for another scheme or a URI without an authority. The authority
         * ends at the first `/`, `?` or `#`; user info (up to its last `@`) and a port are cut off. Nothing else is read.
         */
        private fun hostOf(uri: String): String? {
            val text = uri.trim()
            val colon = text.indexOf(':')
            if (colon <= 0 || !text.substring(0, colon).equals(SCHEME, ignoreCase = true)) return null
            val rest = text.substring(colon + 1)
            if (!rest.startsWith("//")) return null
            val after = rest.substring(2)
            val end = after.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) after.length else it }
            val authority = after.substring(0, end)
            val hostPort = authority.substring(authority.lastIndexOf('@') + 1)
            return hostPort.substringBefore(':').lowercase()
        }
    }
}
