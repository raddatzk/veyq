package me.raddatz.veyq.mobile.sharing

/**
 * The screen a `veyq:` link opens (ADR 0150 § O1): **`veyq://link`** opens *Open a link* — on the sign-in or the unlock
 * screen, and signed in over the open tenant (owner 2026-11-19) —; **`veyq://open`** carrying a place the web's *Open in
 * the app* names ([At], inbox #1415) opens that place; **`veyq://open`** and every other input open the app where it is.
 *
 * A custom scheme is owned by no one: any app on the device may claim `veyq:` and receive what a page sends there, and the
 * link reaches the browser's history and, on the desktops, the handler's command line. So the link carries no name and no
 * key: `veyq://link` is read by its host alone (a crafted `veyq://link#k=…` or `veyq://link?u=https://…` does exactly what
 * `veyq://link` does), and `veyq://open` at most an origin and opaque ids, read by [PlaceLink.ofDeepLink]'s allow-list —
 * anything else in it is ignored, never stored, never logged, never handed on.
 */
sealed interface DeepLink {
    /** The app opened where it is (`veyq://open` with no place, and anything that is neither `veyq://link` nor [At]). */
    data object Open : DeepLink

    /** *Open a link* (`veyq://link`): the person pastes the link they copied there. */
    data object Link : DeepLink

    /**
     * A place `veyq://open` names (inbox #1415): handed to the open shell's [me.raddatz.veyq.mobile.navigation.ShellModel.arrive],
     * or held in memory until a shell opens. Its [toString] names nothing of it.
     */
    class At internal constructor(val place: me.raddatz.veyq.mobile.navigation.PlaceLink) : DeepLink {
        override fun toString(): String = "At"
    }

    companion object {
        private const val SCHEME = "veyq"
        private const val LINK_HOST = "link"

        /**
         * What [uri] opens: [Link] for a `veyq:` URI whose host is `link` (case aside), [At] for a `veyq://open` carrying a
         * place of the allow-list, [Open] for anything else.
         */
        fun parse(uri: String): DeepLink =
            if (hostOf(uri) == LINK_HOST) Link else me.raddatz.veyq.mobile.navigation.PlaceLink.ofDeepLink(uri)?.let(::At) ?: Open

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
