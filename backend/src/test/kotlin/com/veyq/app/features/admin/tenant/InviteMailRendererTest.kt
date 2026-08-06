package com.veyq.app.features.admin.tenant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **content-boundary guard** (ADR 0049), unit-level: proves veyq's renderer can interpolate only the closed,
 * non-secret variable set and nothing else. This is the promoted guard the CR rests on — if someone widens the
 * render context or the variable set without conscious intent, a case here bites.
 */
class InviteMailRendererTest {

    private val renderer = InviteMailRenderer()
    private val ctx = MailContext(
        link = "https://family.example.com/tenant/accept#the-secret-token",
        domain = "family.example.com",
        expiry = "2026-07-31 12:00 UTC",
    )

    @Test
    fun substitutesOnlyTheClosedSet() {
        val out = renderer.render("Join {{domain}} via {{link}} before {{expiry}}", ctx)
        assertEquals("Join family.example.com via https://family.example.com/tenant/accept#the-secret-token before 2026-07-31 12:00 UTC", out)
    }

    @Test
    fun theRenderContextIsClosedToExactlyThreeNonSecretFields() {
        // MailContext is the ONLY thing render() can read. If a field is added here, this bites — widening the
        // set of values that can enter a mail is a conscious decision (update VARIABLES + this guard together),
        // never a silent one. This is the mechanical half of the ADR 0049 boundary.
        val fields = MailContext::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("link", "domain", "expiry"), fields, "the mail render context must stay closed to the 3 non-secret fields")
        assertEquals(setOf("link", "domain", "expiry"), InviteMailRenderer.VARIABLES, "the variable set must match the closed context")
    }

    @Test
    fun rejectsTagsOutsideTheClosedSet() {
        // The exact vectors ADR 0049 forbids veyq from injecting: scope/group/memory names, member lists, names.
        assertEquals(setOf("scope"), renderer.unknownTags("Welcome to {{scope}}, accept {{link}}"))
        assertEquals(setOf("member", "realName"), renderer.unknownTags("{{member}} ({{realName}}) invited you to {{domain}}"))
        assertEquals(setOf("groupName"), renderer.unknownTags("Album {{groupName}}"))
    }

    @Test
    fun acceptsTemplatesUsingOnlyTheClosedSetOrNoTags() {
        assertTrue(renderer.unknownTags("Plain {{link}} {{domain}} {{expiry}}").isEmpty())
        assertTrue(renderer.unknownTags("No tags at all").isEmpty())
        assertTrue(renderer.unknownTags(null).isEmpty())
        // inner whitespace is tolerated on a valid tag
        assertTrue(renderer.unknownTags("{{ link }}").isEmpty())
    }

    @Test
    fun theShippedDefaultsAreContentFreeAndValid() {
        // The defaults themselves reference only the closed set — no sealed field can ride the default mail.
        assertTrue(renderer.unknownTags(renderer.defaultSubject()).isEmpty())
        assertTrue(renderer.unknownTags(renderer.defaultBody()).isEmpty())
        val body = renderer.render(renderer.defaultBody(), ctx)
        assertTrue(body.contains(ctx.link) && body.contains(ctx.domain) && body.contains(ctx.expiry))
        assertFalse(body.contains("{{"), "every tag in the default body must be substituted")
    }

    @Test
    fun anOutOfSetTagIsLeftVerbatimAsADefensiveBackstop() {
        // Write-time validation already forbids this, so it can't reach render(); if it ever did, the renderer
        // must NOT resolve it to anything — it stays literal, never a value.
        assertEquals("Hi {{scope}} at family.example.com", renderer.render("Hi {{scope}} at {{domain}}", ctx))
    }
}
