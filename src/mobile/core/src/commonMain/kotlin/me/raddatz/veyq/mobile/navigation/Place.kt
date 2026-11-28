package me.raddatz.veyq.mobile.navigation

import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.Naming
import me.raddatz.veyq.mobile.search.SettingsSection
import me.raddatz.veyq.mobile.sharing.ReceivedLink
import me.raddatz.veyq.mobile.tree.DisplayNames

/**
 * The place the shell stands on, as the core keeps it for the next start (inbox #1415): the space's id and the ids of the
 * node on screen and of its ancestors, nearest first ([path]; empty: the space's root). Ids only, and sealed as an entry
 * of the account's offline store (`AccountOffline.keepPlace`), so nothing of it is in clear on disk; the ancestors let a
 * node gone since fall back to its nearest parent still listed.
 */
@Serializable
data class KeptPlace(val scopeId: String, val path: List<String> = emptyList())

/** What a [PlaceLink] did ([ShellModel.arrive]). */
sealed class Arrived {
    /** The shell moved there (or as near as what is listed allows). */
    data object Opened : Arrived()

    /** The link is for no tenant open here, or names no space listed: nothing moved. */
    data object NotHere : Arrived()

    /**
     * Held in memory until the shell opens (a remembered account's unlock is on screen): the platform hands it to that
     * shell's [ShellModel.arrive] then — nothing moves for another tenant.
     */
    data object Waiting : Arrived()

    /** A Settings link: the platform opens its settings page at [section] (null: the page's top), as a search's result does. */
    data class Settings(val section: String?) : Arrived()
}

/**
 * A place reached from outside the app (inbox #1415): the web's *Open in the app* (`veyq://open?i=<origin>&s=<scope>[&n=<node>]`
 * or `&settings=<section>`, [ofDeepLink]), or a member's web URL shared into the app or pasted into *Open a link* ([ofWeb]).
 * The four rules: it is only ever opened over a tenant open here whose origin is [origin] ([ShellModel.arrive]) — an unknown
 * origin starts no sign-in and no fetch —; only a view from the allow-list parses (a space, a folder, an application's page,
 * a Settings section — never a token, an action, an invite, a recovery or confirm link, a share link `/s/…`); it only
 * navigates, through the shell's own open over what is listed; and it is never stored or logged ([toString] names nothing).
 */
class PlaceLink internal constructor(
    /** The tenant's origin the link is for (`scheme://host[:port]`, a default port dropped, lower case). */
    internal val origin: String,
    internal val target: Target,
) {
    internal sealed interface Target {
        /** A `veyq:` link's place: opaque ids only. */
        class Ids(val scopeId: String, val nodeId: String?) : Target

        /** A web URL's fragment: the scope token (`""` the tenant, `~me`, `@<group>`) and the slug path ([RoutePath]). */
        class Route(val scope: String, val segments: List<String>, val folder: Boolean) : Target

        /** A Settings section ([SettingsSection.id]), or the page's top. */
        class Settings(val section: SettingsSection?) : Target
    }

    override fun toString(): String = "PlaceLink"

    companion object {
        /** The longest text read as a place (a share link's bound, `ReceivedLink.MAX_LINK_LENGTH`). */
        private const val MAX = ReceivedLink.MAX_LINK_LENGTH

        /** Settings sections that are one action as a whole: never opened from outside. */
        private val NOT_FROM_OUTSIDE = setOf(SettingsSection.ERASE, SettingsSection.SIGN_OUT, SettingsSection.TRUST_RESET)

        /**
         * A `veyq://open` link's place, or null where it carries none of the allowed shapes (it then opens the app where it
         * is, ADR 0150 § O1): exactly `i` (an http(s) origin and nothing else) with `s` (a scope id) and optionally `n` (a
         * node id), or `i` with `settings`. Any other parameter, a repeated one, a fragment, an id not shaped as one: null.
         */
        fun ofDeepLink(uri: String): PlaceLink? {
            val text = uri.trim()
            if (text.length > MAX || !text.startsWith("veyq://open?", ignoreCase = true)) return null
            val query = text.substring("veyq://open?".length)
            if ('#' in query) return null
            val params = HashMap<String, String>()
            for (p in query.split('&')) {
                val eq = p.indexOf('=')
                if (eq <= 0) return null
                val key = p.substring(0, eq)
                if (key in params) return null
                params[key] = percentDecoded(p.substring(eq + 1)) ?: return null
            }
            val origin = params["i"]?.let(::originOf) ?: return null
            val section = params["settings"]
            if (section != null) {
                if (params.keys != setOf("i", "settings")) return null
                return settings(section)?.let { PlaceLink(origin, it) }
            }
            if (params.keys != setOf("i", "s") && params.keys != setOf("i", "s", "n")) return null
            val scope = params.getValue("s").takeIf(::isId) ?: return null
            val node = params["n"]
            if (node != null && !isId(node)) return null
            return PlaceLink(origin, Target.Ids(scope.lowercase(), node?.lowercase()))
        }

        /**
         * A member's web URL as a place, or null where it is none the allow-list holds: `<origin>/` with a fragment of the
         * web's grammar (`location.ts`: a scope token, then slug segments, a trailing `/` marking a folder), or
         * `<origin>/settings[/<section>]` with no fragment. No query, no credentials, no person's page (`#people/…`), and
         * every segment a slug (`[a-z0-9-]`) — so no token, key or action of another page parses as a place.
         */
        fun ofWeb(input: String): PlaceLink? {
            val text = input.trim()
            if (text.isEmpty() || text.length > MAX) return null
            val url = ReceivedLink.Url.of(text) ?: return null
            if ((url.scheme != "https" && url.scheme != "http") || url.credentials || url.query != null) return null
            val path = url.path
            if (path == "/settings" || path == "/settings/") return if (url.fragment.isEmpty()) PlaceLink(url.origin, Target.Settings(null)) else null
            if (path.startsWith("/settings/")) {
                if (url.fragment.isNotEmpty()) return null
                return settings(path.removePrefix("/settings/"))?.let { PlaceLink(url.origin, it) }
            }
            if (path != "/") return null
            return route(url.fragment)?.let { PlaceLink(url.origin, it) }
        }

        /** The web's `parseHash`, held to the allow-list: null for a person's page or anything not of the grammar. */
        private fun route(fragment: String): Target.Route? {
            val raw = fragment
            val parts = ArrayList<String>()
            for (p in raw.split('/')) if (p.isNotEmpty()) parts += percentDecoded(p) ?: return null
            if (parts.isEmpty()) return Target.Route("", emptyList(), false)
            val head = parts[0]
            val slash = raw.endsWith("/")
            val scoped = head == PERSONAL || (head.startsWith("@") && isSlug(head.substring(1)))
            if (!scoped && head.startsWith("@")) return null
            if (!scoped && parts.size == 2 && head == PEOPLE) return null
            val segments = if (scoped) parts.drop(1) else parts
            if (!segments.all(::isSlug)) return null
            return Target.Route(if (scoped) head else "", segments, slash && segments.isNotEmpty())
        }

        private fun settings(id: String): Target.Settings? =
            SettingsSection.of(id)?.takeIf { it !in NOT_FROM_OUTSIDE }?.let { Target.Settings(it) }

        /** An http(s) origin alone: no credentials, no path beyond `/`, no query, no fragment. */
        private fun originOf(text: String): String? {
            val url = ReceivedLink.Url.of(text) ?: return null
            if ((url.scheme != "https" && url.scheme != "http") || url.credentials || url.query != null || url.fragment.isNotEmpty() || url.path != "/") return null
            if (text.contains('#') || text.trimEnd('/').substringAfter("://").contains('/')) return null
            return url.origin
        }

        /** Percent-decoded ASCII, or null for a malformed escape or anything outside printable ASCII. */
        private fun percentDecoded(s: String): String? {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length) return null
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    if (v < 0x20 || v > 0x7e) return null
                    out.append(v.toChar())
                    i += 3
                } else {
                    if (c.code < 0x20 || c.code > 0x7e) return null
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /** A UUID as the server mints ids (8-4-4-4-12 hex digits, either case). */
        private fun isId(s: String): Boolean {
            if (s.length != 36) return false
            for ((i, c) in s.withIndex()) {
                val dash = i == 8 || i == 13 || i == 18 || i == 23
                if (dash != (c == '-')) return false
                if (!dash && c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') return false
            }
            return true
        }

        /** What `slugify` emits: `[a-z0-9-]`, at least one. */
        private fun isSlug(s: String): Boolean = s.isNotEmpty() && s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

        /** The personal scope's token (`location.ts` `PERSONAL_SCOPE`). */
        internal const val PERSONAL = "~me"

        /** The platform's route word for a person's profile (`location.ts` `PEOPLE_SEGMENT`). */
        internal const val PEOPLE = "people"
    }
}

/**
 * The web's slug paths below a scope's root (`src/web/src/core/node/location.ts`' `segmentsOf` and `resolveSlugPath`,
 * ported for [PlaceLink.ofWeb]): a node's segment is `slugify(name)`, equal ones numbered `-2`, `-3`, … within a class of
 * siblings (the folders; one application's nodes; the rest) in creation order, an application's route slug reserved among
 * the unprefixed names (and `people` at a scope's root), an application's slug written where the path enters it.
 */
internal object RoutePath {
    private fun routeOf(kind: String): String? = AppKinds.facts.kinds.firstOrNull { it.kind == kind }?.route

    private fun groupOf(n: NodeRef): String {
        if (n.isFolder) return "folder"
        val slug = routeOf(n.kind)
        return if (slug != null) "application:$slug" else "none"
    }

    private fun routeSlugs(): Set<String> = AppKinds.facts.kinds.mapNotNull { it.route }.toSet()

    /** Each of one parent's [siblings]' segment, by node id (`segmentsOf`). */
    fun segmentsOf(siblings: List<NodeRef>): Map<String, String> {
        val atRoot = siblings.isNotEmpty() && siblings.all { it.parentId == null }
        val reserved = if (atRoot) routeSlugs() + PlaceLink.PEOPLE else routeSlugs()
        class Bucket(val group: String, val plain: String, val unprefixed: Boolean, val nodes: MutableList<NodeRef>)
        val buckets = LinkedHashMap<String, Bucket>()
        for (n in siblings) {
            val group = groupOf(n)
            val plain = Naming.slugify(n.name)
            val key = "$group\u0000$plain"
            buckets.getOrPut(key) { Bucket(group, plain, n.isFolder || routeOf(n.kind) == null, ArrayList()) }.nodes += n
        }
        val segments = HashMap<String, String>()
        for (b in buckets.values) {
            val sorted = b.nodes.sortedWith { x, y -> DisplayNames.compareByCreation(x.createdAt, x.nodeId, y.createdAt, y.nodeId) }
            val keeper = if (b.unprefixed && b.plain in reserved) null else sorted[0]
            if (keeper != null) segments[keeper.nodeId] = b.plain
            var suffix = 1
            for (n in if (keeper != null) sorted.drop(1) else sorted) {
                do suffix++ while (buckets.containsKey("${b.group}\u0000${b.plain}-$suffix"))
                segments[n.nodeId] = "${b.plain}-$suffix"
            }
        }
        return segments
    }

    /**
     * The node [segments] name below the scope's root in [tree] (its nodes): null the root, a node id, or false where no
     * reading of the path matches (`resolveSlugPath`, with its backtracking).
     */
    fun resolve(tree: List<NodeRef>, segments: List<String>, folder: Boolean): Any? {
        val children = tree.groupBy { it.parentId }
        val names = HashMap<String?, Map<String, String>>()
        fun namesOf(parent: String?) = names.getOrPut(parent) { segmentsOf(children[parent].orEmpty()) }
        val slugs = routeSlugs()
        val byId = tree.associateBy { it.nodeId }
        val count = segments.size
        fun walk(parentId: String?, i: Int): Any? {
            if (i == count) return parentId
            val seg = segments[i]
            val parentSlug = parentId?.let { byId[it] }?.let { routeOf(it.kind) }
            val kids = children[parentId].orEmpty()
            val ns = namesOf(parentId)
            fun into(kid: NodeRef, next: Int): Any? = if (next == count && folder && !kid.isFolder) false else walk(kid.nodeId, next)
            if (seg in slugs && seg != parentSlug && i + 1 < count) {
                for (kid in kids) {
                    if (routeOf(kid.kind) == seg && ns[kid.nodeId] == segments[i + 1]) {
                        val r = into(kid, i + 2)
                        if (r != false) return r
                    }
                }
            }
            val plain = kids.filter { k -> routeOf(k.kind).let { it == null || it == parentSlug } && ns[k.nodeId] == seg }
            val folderFirst = i < count - 1
            for (kid in plain.filter { it.isFolder == folderFirst } + plain.filter { it.isFolder != folderFirst }) {
                val r = into(kid, i + 1)
                if (r != false) return r
            }
            return false
        }
        return walk(null, 0)
    }

    /** The node the longest resolving prefix of [segments] names (null: the root): a place gone opens its nearest parent. */
    fun nearest(tree: List<NodeRef>, segments: List<String>, folder: Boolean): String? {
        for (k in segments.size downTo 1) {
            val r = resolve(tree, segments.subList(0, k), folder && k == segments.size)
            if (r != false) return r as String?
        }
        return null
    }
}
