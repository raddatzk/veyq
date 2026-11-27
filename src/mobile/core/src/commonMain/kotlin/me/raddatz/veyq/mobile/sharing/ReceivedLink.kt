package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
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
        val host: String get() = hostOf(origin)

        /** `K_share`, decoded from the fragment: a fresh copy each call, the caller's to zero. */
        @PlatformApi
        @HiddenFromObjC
        fun key(): ByteArray = unb64url(link.substringAfter("#k="))!!

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

    // The shapes are read character by character, with this file's own small helpers below in place of the standard
    // library's `Regex`, `split`, `indexOf`, `startsWith` and `trim`: on JavaScript those reach the library's general
    // paths (a `Regex`'s wrapper, the delimited-ranges sequence, the ignore-case searches, a Unicode whitespace table),
    // ~5 KB gz of the web's bundle against a rule of a few hundred bytes (ADR 0155 § The share links' slice). And a
    // character class is read in JavaScript's `u` mode there, where `^\\[[0-9a-f:.]+]$` — the IPv6 host's pattern this
    // replaced — is no valid expression at all: the web's first call threw.

    /**
     * A member's or an owner's invite as a person received it (`<origin>/tenant/accept#<token>[.<sealed>[.<code>]]`, ADR
     * 0111 decision 1, ADR 0129 decision 1): its server's [origin] and the [fragment] the join reads. [fragment] holds the
     * invite's secret parts; this object lives in the join's model only, and its [toString] names the origin alone.
     */
    class InviteLink internal constructor(val origin: String, @PlatformApi @HiddenFromObjC val fragment: String) {
        val host: String get() = hostOf(origin)
        override fun toString(): String = "InviteLink($origin)"
    }

    /**
     * [input] as an invite link, or null: an `https` URL (`http` on a development host) without credentials whose path
     * is `/tenant/accept` and whose fragment is not empty. What the fragment holds is the join's to read
     * (`Invites.parseFragment`); the server sees only its token.
     */
    fun inviteOf(input: String): InviteLink? {
        val text = trim(input)
        if (text.isEmpty() || text.length > MAX_LINK_LENGTH) return null
        val url = Url.of(text) ?: return null
        if (url.scheme != "https" && !(url.scheme == "http" && devHost(url.host))) return null
        if (url.credentials || url.path != "/tenant/accept" || url.fragment.isEmpty()) return null
        return InviteLink(url.origin, url.fragment)
    }

    /** The web's `parseShareLink`: a moderator's review is [Problem.NO_KEY] here, as a bookmark's parse calls it. */
    fun parse(input: String): Parsed {
        val text = trim(input)
        if (text.isEmpty()) return Parsed.Refused(Problem.EMPTY)
        if (text.length > MAX_LINK_LENGTH) return Parsed.Refused(Problem.TOO_LONG)
        val url = Url.of(text) ?: return Parsed.Refused(Problem.NOT_A_URL)
        if (url.scheme != "https" && !(url.scheme == "http" && devHost(url.host))) {
            return Parsed.Refused(if (url.scheme == "http") Problem.NOT_HTTPS else Problem.NOT_A_URL)
        }
        if (url.credentials) return Parsed.Refused(Problem.CREDENTIALS)
        if (!startsAt(url.path, "/s/", 0) || !isToken(url.path, 3)) return Parsed.Refused(Problem.NOT_A_SHARE)
        val shareId = url.path.substring(3)
        val key = fragmentKey(url.fragment)
        if (key == null || !isKey(key)) return Parsed.Refused(Problem.NO_KEY)
        val link = "${url.origin}/s/$shareId#k=$key"
        if (link.length > MAX_LINK_LENGTH) return Parsed.Refused(Problem.TOO_LONG)
        return Parsed.Ok(ShareLink(link, url.origin, shareId), asksForReport(url.fragment))
    }

    /** The host a link's [origin] names, as a reader is shown it (`photos.example.org`, a port kept). */
    fun hostOf(origin: String): String = origin.substring(find(origin, ':', 0) + 3)

    /** Whether a share page's fragment (with or without its `#`) asks it to open its report dialog: the `report` parameter
     *  of [ShareLink.reportLink] among the `&`-separated ones. */
    fun asksForReport(fragment: String): Boolean = "report" in params(fragment)

    /** The fragment without the `report` parameter (and without empty ones): what the share page keeps in its address once
     *  it has read it, so a reload or a copied address does not open the dialog again. */
    fun withoutReport(fragment: String): String {
        var kept = ""
        for (p in params(fragment)) if (p != "report" && p.isNotEmpty()) kept += (if (kept.isEmpty()) "#" else "&") + p
        return kept
    }

    /** What the app's *Open a link* takes: [parse], a moderator's review (`#moderation`) refused as its own problem. */
    fun open(input: String): Parsed {
        val parsed = parse(input)
        val text = trim(input)
        val hash = find(text, '#', 0).let { if (it < 0) "" else text.substring(it) }
        return if (parsed is Parsed.Refused && hash == "#moderation") Parsed.Refused(Problem.MODERATION) else parsed
    }

    /** A kept link read back (the web's `keptLink`): it must be the normalized form itself; null otherwise. */
    fun kept(link: String?): ShareLink? {
        if (link == null) return null
        val parsed = parse(link) as? Parsed.Ok ?: return null
        return parsed.link.takeIf { it.link == link }
    }

    private fun devHost(host: String): Boolean =
        host == "localhost" || (host.length > 10 && startsAt(host, ".localhost", host.length - 10)) || host == "127.0.0.1" || host == "[::1]"

    /** The fragment's `k=`, among `&`-separated parameters, as the share page reads it (the first); null when there is none. */
    private fun fragmentKey(fragment: String): String? {
        for (p in params(fragment)) if (startsAt(p, "k=", 0)) return p.substring(2)
        return null
    }

    /**
     * Whether [key] is a 32-byte key in base64url: 43 characters of its alphabet whose last one leaves the two bits past
     * the 256th zero — a canonical encoding, as the share page writes it (a strict decode refuses the others; the browser's
     * `atob` would read them). Decided by its characters, so no decoder runs on the parse.
     */
    private fun isKey(key: String): Boolean = isToken(key, 0) && key[42] in CANONICAL_LAST

    private const val CANONICAL_LAST = "AEIMQUYcgkosw048"

    /** Whether [s] from [from] on is a 32-byte token's 43 base64url characters (`sharing.openapi.yaml` `ShareId`). */
    private fun isToken(s: String, from: Int): Boolean {
        if (s.length - from != 43) return false
        for (i in from until s.length) if (!(alnum(s[i], upper = true) || s[i] == '-' || s[i] == '_')) return false
        return true
    }

    private fun alnum(c: Char, upper: Boolean): Boolean = c in 'a'..'z' || c in '0'..'9' || (upper && c in 'A'..'Z')

    /** A URL scheme: a letter, then letters, digits, `+`, `.` and `-`. */
    private fun isScheme(s: String): Boolean {
        if (s.isEmpty() || !alnum(s[0], upper = true) || s[0] in '0'..'9') return false
        for (c in s) if (!(alnum(c, upper = true) || c == '+' || c == '.' || c == '-')) return false
        return true
    }

    /** A host as the WHATWG parser keeps it unchanged (lower case already): a name of ASCII, or an IPv6 literal in brackets. */
    private fun isHost(h: String): Boolean {
        if (h.isEmpty()) return false
        val v6 = h.length > 2 && h[0] == '[' && h[h.length - 1] == ']'
        for (i in (if (v6) 1 else 0) until (if (v6) h.length - 1 else h.length)) {
            val c = h[i]
            val ok = if (v6) c in '0'..'9' || c in 'a'..'f' || c == ':' || c == '.' else alnum(c, upper = false) || c in "._~-"
            if (!ok) return false
        }
        return true
    }

    /** The index of [c] in [s] from [from] on, or -1. */
    private fun find(s: String, c: Char, from: Int): Int {
        for (i in from until s.length) if (s[i] == c) return i
        return -1
    }

    /** Whether [s] holds [part] at [at]. */
    private fun startsAt(s: String, part: String, at: Int): Boolean {
        if (at < 0 || at + part.length > s.length) return false
        for (i in part.indices) if (s[at + i] != part[i]) return false
        return true
    }

    /** A fragment's `&`-separated parameters, its leading `#` dropped (empty ones kept, as JavaScript's `split` keeps them). */
    private fun params(fragment: String): List<String> {
        val out = ArrayList<String>()
        var from = if (fragment.isNotEmpty() && fragment[0] == '#') 1 else 0
        while (true) {
            val amp = find(fragment, '&', from)
            out.add(fragment.substring(from, if (amp < 0) fragment.length else amp))
            if (amp < 0) return out
            from = amp + 1
        }
    }

    /** [s] without the white space JavaScript's `String.prototype.trim` drops at either end (the web's reading, one for all). */
    private fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && space(s[a])) a++
        while (b > a && space(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun space(c: Char): Boolean =
        c == ' ' || c in '\t'..'\r' || c == '\u00a0' || c == '\u1680' || c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' ||
            c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff'

    /** A base64url string without padding decoded, or null. */
    internal fun unb64url(s: String): ByteArray? = runCatching {
        val std = s.replace('-', '+').replace('_', '/')
        unb64(std + "=".repeat((4 - std.length % 4) % 4))
    }.getOrNull()

    /** The parts of an absolute URL this check reads; null where the WHATWG parser would refuse it (or read it otherwise). */
    private class Url(val scheme: String, val host: String, val origin: String, val credentials: Boolean, val path: String, val fragment: String) {
        companion object {
            fun of(raw: String): Url? {
                // the WHATWG parser drops every tab and newline inside the input, and reads `\` as `/` in a special URL
                var text = ""
                for (c in raw) if (c != '\t' && c != '\n' && c != '\r') text += c
                val colon = find(text, ':', 0)
                if (colon <= 0 || !isScheme(text.substring(0, colon))) return null
                val scheme = text.substring(0, colon).lowercase()
                if (scheme != "http" && scheme != "https") {
                    // another scheme parses (ftp:, javascript:), and is refused by the caller as no link it takes
                    return Url(scheme, "", "", false, "", "")
                }
                // a special scheme: any run of slashes (either way) before the authority
                var i = colon + 1
                while (i < text.length && (text[i] == '/' || text[i] == '\\')) i++
                var end = i
                while (end < text.length && text[end] != '/' && text[end] != '\\' && text[end] != '?' && text[end] != '#') end++
                val authority = text.substring(i, end)
                var at = authority.length - 1
                while (at >= 0 && authority[at] != '@') at--
                val hostPort = authority.substring(at + 1)
                // the host and its port (-1: none given, -2: an empty one)
                var host = hostPort
                var port = -1
                val close = if (hostPort.isNotEmpty() && hostPort[0] == '[') find(hostPort, ']', 0) else -1
                if (hostPort.isNotEmpty() && hostPort[0] == '[' && close < 0) return null
                var colonAt = hostPort.length - 1
                while (colonAt > close && hostPort[colonAt] != ':') colonAt--
                if (colonAt > close) {
                    if (close >= 0 && colonAt != close + 1) return null
                    host = hostPort.substring(0, colonAt)
                    val digits = hostPort.substring(colonAt + 1)
                    if (digits.length > 5) return null
                    port = if (digits.isEmpty()) -2 else 0
                    for (c in digits) if (c in '0'..'9') port = port * 10 + (c - '0') else return null
                    if (port > 65535) return null
                } else if (close >= 0 && close != hostPort.length - 1) return null
                host = host.lowercase()
                if (!isHost(host)) return null
                val explicit = port >= 0 && port != if (scheme == "https") 443 else 80
                val origin = "$scheme://$host" + (if (explicit) ":$port" else "")
                val hash = find(text, '#', end)
                val query = find(text, '?', end)
                val pathEnd = if (query in end until (if (hash < 0) text.length else hash)) query else if (hash < 0) text.length else hash
                var path = ""
                for (k in end until pathEnd) path += if (text[k] == '\\') '/' else text[k]
                return Url(scheme, host, origin, at > 0, path.ifEmpty { "/" }, if (hash < 0) "" else text.substring(hash + 1))
            }
        }
    }
}
