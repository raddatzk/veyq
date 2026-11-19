package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.crypto.unb64

/**
 * A share link as a person received it (ADR 0150 decision 2): the web's `parseShareLink`, `keptLink`, `hostOf` and
 * `asksForReport` (`src/web/src/core/bookmark/bookmarkLink.ts`) ported exactly, both held to
 * `contracts/platform/share-links/cases.v1.json` (`ReceivedLinkTest`). A link is `<origin>/s/<shareId>#k=<base64url
 * K_share>`; what is kept of it is exactly that — any query and any other fragment parameter dropped — so two pastes of one
 * link are one string and nothing but the key travels on. Pure: never fetched here.
 *
 * The URL is read as the WHATWG parser reads the inputs a share link can be (a special scheme's authority, its host
 * lower-cased, a default port dropped); an input it would read in some other way — an IDN host, a percent-encoded
 * path — is refused here (fail closed): the link was then not one the web's dialog builds.
 */
object ReceivedLink {
    /** The longest link kept (`contracts/platform/share-bookmark-meta.v1.schema.json`). */
    const val MAX_LINK_LENGTH = 2048

    /** Why a text is no link to open or keep; [MODERATION] is the app's own (a moderator's review, decision 8). */
    enum class Problem { EMPTY, TOO_LONG, NOT_A_URL, NOT_HTTPS, CREDENTIALS, NOT_A_SHARE, NO_KEY, MODERATION }

    /**
     * A link that may be opened or kept: its normalized form, its origin and its share id. [link] holds `K_share`: this
     * object lives in the open link's model only, and its [toString] names the origin alone.
     */
    class ShareLink internal constructor(val link: String, val origin: String, val shareId: String) {
        /** The host a kept link names, as a reader is shown it (`photos.example.org`, a port kept). */
        val host: String get() = origin.substringAfter("://")

        /** `K_share`, decoded from the fragment: a fresh copy each call, the caller's to zero. */
        internal fun key(): ByteArray = unb64url(link.substringAfter("#k="))!!

        /** The same link with the share page asked to open its report dialog (ADR 0120 decision 6). */
        val reportLink: String get() = "$link&report"

        override fun equals(other: Any?): Boolean = other is ShareLink && other.link == link
        override fun hashCode(): Int = link.hashCode()
        override fun toString(): String = "ShareLink($origin)"
    }

    sealed class Parsed {
        /** [report]: the fragment asked the share page to open its report dialog (`&report`), never kept. */
        class Ok(val link: ShareLink, val report: Boolean) : Parsed()
        data class Refused(val problem: Problem) : Parsed()
    }

    private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    private val SHARE_PATH = Regex("^/s/([A-Za-z0-9_-]{43})$")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
    private val HOST = Regex("^[a-z0-9._~-]+$")
    private val IPV6 = Regex("^\\[[0-9a-f:.]+]$")

    /** The web's `parseShareLink`: a moderator's review is [Problem.NO_KEY] here, as a bookmark's parse calls it. */
    fun parse(input: String): Parsed {
        val text = input.trim()
        if (text.isEmpty()) return Parsed.Refused(Problem.EMPTY)
        if (text.length > MAX_LINK_LENGTH) return Parsed.Refused(Problem.TOO_LONG)
        val url = Url.of(text) ?: return Parsed.Refused(Problem.NOT_A_URL)
        if (url.scheme != "https" && !(url.scheme == "http" && devHost(url.host))) {
            return Parsed.Refused(if (url.scheme == "http") Problem.NOT_HTTPS else Problem.NOT_A_URL)
        }
        if (url.credentials) return Parsed.Refused(Problem.CREDENTIALS)
        val shareId = SHARE_PATH.matchEntire(url.path)?.groupValues?.get(1) ?: return Parsed.Refused(Problem.NOT_A_SHARE)
        val key = fragmentKey(url.fragment)
        if (key == null || !isKey(key)) return Parsed.Refused(Problem.NO_KEY)
        val link = "${url.origin}/s/$shareId#k=$key"
        if (link.length > MAX_LINK_LENGTH) return Parsed.Refused(Problem.TOO_LONG)
        return Parsed.Ok(ShareLink(link, url.origin, shareId), url.fragment.split('&').contains("report"))
    }

    /** What the app's *Open a link* takes: [parse], a moderator's review (`#moderation`) refused as its own problem. */
    fun open(input: String): Parsed {
        val parsed = parse(input)
        val text = input.trim()
        val hash = text.indexOf('#').let { if (it < 0) "" else text.substring(it) }
        return if (parsed is Parsed.Refused && hash == "#moderation") Parsed.Refused(Problem.MODERATION) else parsed
    }

    /** A kept link read back (the web's `keptLink`): it must be the normalized form itself; null otherwise. */
    fun kept(link: String?): ShareLink? {
        if (link == null) return null
        val parsed = parse(link) as? Parsed.Ok ?: return null
        return parsed.link.takeIf { it.link == link }
    }

    private fun devHost(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "[::1]"

    private fun fragmentKey(fragment: String): String? =
        fragment.split('&').firstOrNull { it.startsWith("k=") }?.substring(2)

    private fun isKey(key: String): Boolean = TOKEN.matches(key) && unb64url(key)?.size == 32

    /** A base64url string without padding decoded, or null. */
    internal fun unb64url(s: String): ByteArray? = runCatching {
        val std = s.replace('-', '+').replace('_', '/')
        unb64(std + "=".repeat((4 - std.length % 4) % 4))
    }.getOrNull()

    /** The parts of an absolute URL this check reads; null where the WHATWG parser would refuse it (or read it otherwise). */
    private class Url(val scheme: String, val host: String, val origin: String, val credentials: Boolean, val path: String, val fragment: String) {
        companion object {
            fun of(raw: String): Url? {
                // the WHATWG parser drops every tab and newline inside the input
                val text = raw.filterNot { it == '\t' || it == '\n' || it == '\r' }
                val colon = text.indexOf(':')
                if (colon <= 0 || !SCHEME.matches(text.substring(0, colon))) return null
                val scheme = text.substring(0, colon).lowercase()
                if (scheme != "http" && scheme != "https") {
                    // another scheme parses (ftp:, javascript:), and is refused by the caller as no link it takes
                    return Url(scheme, "", "", false, "", "")
                }
                // a special scheme: any run of slashes (either way) before the authority
                var i = colon + 1
                while (i < text.length && (text[i] == '/' || text[i] == '\\')) i++
                val end = (i until text.length).firstOrNull { text[it] == '/' || text[it] == '\\' || text[it] == '?' || text[it] == '#' } ?: text.length
                val authority = text.substring(i, end)
                val at = authority.lastIndexOf('@')
                val credentials = at > 0
                val hostPort = if (at >= 0) authority.substring(at + 1) else authority
                val (hostRaw, port) = splitPort(hostPort) ?: return null
                val host = hostRaw.lowercase()
                if (host.isEmpty() || !(HOST.matches(host) || IPV6.matches(host))) return null
                if (!port.isNullOrEmpty() && (port.length > 5 || !port.all { it.isDigit() } || port.toInt() > 65535)) return null
                val explicit = port?.takeIf { it.isNotEmpty() }?.toInt()?.takeIf { it != if (scheme == "https") 443 else 80 }
                val origin = "$scheme://$host" + (explicit?.let { ":$it" } ?: "")
                val rest = text.substring(end)
                val hash = rest.indexOf('#')
                val beforeHash = if (hash < 0) rest else rest.substring(0, hash)
                val fragment = if (hash < 0) "" else rest.substring(hash + 1)
                val path = beforeHash.substringBefore('?').replace('\\', '/').ifEmpty { "/" }
                return Url(scheme, host, origin, credentials, path, fragment)
            }

            /** The host and its port (null: none given; "" an empty one), or null for an unreadable `[v6]`. */
            private fun splitPort(hostPort: String): Pair<String, String?>? {
                if (hostPort.startsWith("[")) {
                    val close = hostPort.indexOf(']')
                    if (close < 0) return null
                    val after = hostPort.substring(close + 1)
                    return when {
                        after.isEmpty() -> hostPort to null
                        after.startsWith(":") -> hostPort.substring(0, close + 1) to after.substring(1)
                        else -> null
                    }
                }
                val c = hostPort.lastIndexOf(':')
                return if (c < 0) hostPort to null else hostPort.substring(0, c) to hostPort.substring(c + 1)
            }
        }
    }
}
