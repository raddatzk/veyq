package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * A link's guest paths, each compared with the **same request on a share id no link has** (ADR 0088 decision 5, inbox
 * #442): a path *serves* the link when its answer differs from the unknown link's, and *refuses it like an unknown link*
 * when status and body are identical to it and a 404 — what a guest of a switched-off role must see, so it learns
 * nothing of the instance's policy. Every path of every link is asked and every mismatch collected, so one failure names
 * each path that broke: a bite of the one gate (`ShareGuestGate.activeShare`) lists them all, not the first.
 *
 * Each module passes the guest operations it boots ([Path]); a request must be well-formed for an unknown id too, so the
 * answer it is compared with is the guest gate's and not the request validation's.
 */
object GuestPaths {

    /** One guest operation, sent as the same request to whichever share id it is handed. */
    class Path(val name: String, val call: (shareId: String) -> Response)

    private data class Answer(val status: Int, val body: String)

    /**
     * Each share switch against the existing links of every role, by stored switch rows ([CapabilitySwitchFixtures]):
     * all served before; while the switch is off, the links of the roles it reaches refused like an unknown link on
     * every path and the others still served (the leaf switches reach their own role, the parent all three, the AND
     * along the path); all served again once it is back on, because nothing was written onto a link. [links] maps a
     * role to its link's id, [pathsFor] a role to the paths its link is asked on.
     */
    fun assertEachShareSwitchReachesTheExistingLinksOfItsRole(links: Map<String, String>, pathsFor: (role: String) -> List<Path>) {
        val failures = mutableListOf<String>()
        val switches = listOf(
            CapabilityIds.PUBLIC_SHARES to setOf(ShareEntity.READ, ShareEntity.READ_WRITE, ShareEntity.WRITE_ONLY),
            CapabilityIds.PUBLIC_SHARES_CONTRIBUTE to setOf(ShareEntity.READ_WRITE),
            CapabilityIds.PUBLIC_SHARES_DROP_BOX to setOf(ShareEntity.WRITE_ONLY),
        )
        for ((switch, reached) in switches) {
            links.forEach { (role, link) -> failures += served(link, pathsFor(role), "$role link before $switch went off") }
            CapabilitySwitchFixtures.with(switch) {
                links.forEach { (role, link) ->
                    failures += if (role in reached) refusedLikeAnUnknownLink(link, pathsFor(role), "$role link while $switch is off")
                    else served(link, pathsFor(role), "$role link while $switch is off (not its switch)")
                }
            }
            links.forEach { (role, link) -> failures += served(link, pathsFor(role), "$role link after $switch is back on") }
        }
        assertTrue(failures.isEmpty(), "guest paths that broke the share switch:\n" + failures.joinToString("\n"))
    }

    /** The paths on which [link] is NOT answered as an unknown link is — each a failure line naming the path. */
    fun served(link: String, paths: List<Path>, what: String): List<String> = paths.mapNotNull { path ->
        val unknown = answer(path, TestIds.shareId())
        val got = answer(path, link)
        if (got == unknown) "${path.name}: $what — answered as an unknown link ($got), expected to be served" else null
    }

    /** The paths on which [link] is answered other than an unknown link's 404 — each a failure line naming the path. */
    fun refusedLikeAnUnknownLink(link: String, paths: List<Path>, what: String): List<String> = paths.mapNotNull { path ->
        val unknown = answer(path, TestIds.shareId())
        val got = answer(path, link)
        when {
            unknown.status != 404 -> "${path.name}: $what — the unknown link itself answered $unknown, not a 404"
            got != unknown -> "${path.name}: $what — answered $got, expected exactly the unknown link's $unknown"
            else -> null
        }
    }

    private fun answer(path: Path, shareId: String): Answer = path.call(shareId).let { Answer(it.statusCode, it.body.asString()) }
}
