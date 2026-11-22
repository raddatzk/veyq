package me.raddatz.veyq.mobile.composition

import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.offline.Queued
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The composition fills the platform's registry whole (ADR 0154 decision 3): one part per [ApplicationId], no node kind
 * owned twice, every queued write answered by exactly one sender, every per-kind answer given by one part only — so an
 * application's kind is never left to a silent default, and a second part never shadows the first.
 */
class AppCompositionTest {
    init { AppComposition.install() }

    private val parts = AppComposition.applications

    @Test
    fun everyApplicationHasExactlyOnePart() {
        assertEquals(ApplicationId.entries.toSet(), parts.map { it.application }.toSet())
        assertEquals(parts.size, parts.map { it.application }.toSet().size)
    }

    @Test
    fun noKindIsOwnedTwice() {
        val kinds = parts.flatMap { it.kinds }
        assertEquals(kinds.size, kinds.toSet().size, "a node kind owned twice: $kinds")
        assertTrue(kinds.none { it == "FOLDER" || it == "SHARE_BOOKMARK" }, "an application claims a core kind: $kinds")
    }

    @Test
    fun everyQueuedWriteHasExactlyOneSender() {
        val core = setOf(Queued.FOLDER_CREATE, Queued.NODE_RENAME, Queued.NODE_TRASH)
        for (q in Queued.entries) {
            val senders = parts.count { it.queuedSender(q) != null }
            assertEquals(if (q in core) 0 else 1, senders, "$q is sent by $senders application(s)")
            AppKinds.queuedSender(q) // answered, never the registry's error
        }
    }

    @Test
    fun everyKindIsAnsweredByOnePartOnly() {
        val kinds = parts.flatMap { it.kinds } + listOf("FOLDER", "SHARE_BOOKMARK")
        val roles = listOf("READ", "READ_WRITE", "WRITE_ONLY")
        for (kind in kinds) {
            fun once(what: String, answers: Int) = assertTrue(answers <= 1, "$what of $kind answered by $answers parts")
            once("kindFacts", parts.count { p -> p.kindFacts.any { it.kind == kind } })
            once("rekey", parts.count { it.rekey(kind) != null })
            once("headerOrder", parts.count { it.headerOrder(kind) != null })
            once("header", parts.count { it.header(kind) != null })
            once("doors", parts.count { it.doors(kind, AppKinds.Facts()) != null })
            once("lossesOfCopy", parts.count { it.lossesOfCopy(kind) != null })
            once("search", parts.count { it.search(kind) != null })
            once("share", parts.count { it.share(kind) != null })
            once("shareWords", parts.count { it.shareWords(kind) != null })
            once("pins", parts.count { it.pins?.pinnable(kind) == true })
            once("downloadsItself", parts.count { it.downloadsItself(kind) })
            for (role in roles) once("guestDoor($role)", parts.count { it.guestDoor(kind, role) != null })
        }
        // an application's own kind is named, folded and placed by its own part
        for (part in parts) for (kind in part.kinds) {
            assertTrue(part.kindFacts.any { it.kind == kind }, "$kind has no facts (folding, route, placement)")
            assertTrue(part.header(kind) != null, "$kind has no header")
        }
    }

    @Test
    fun aSecondInstallOfOtherPartsFails() {
        AppComposition.install() // the same parts again: no change
        assertFailsWith<IllegalStateException> { AppKinds.install(parts.reversed()) }
        assertFailsWith<IllegalStateException> { AppKinds.install(parts.drop(1)) }
    }
}
