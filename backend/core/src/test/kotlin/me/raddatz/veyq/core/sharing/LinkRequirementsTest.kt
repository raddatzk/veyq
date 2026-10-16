package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements.LINK_MAX_DAYS
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements.LINK_PASSWORD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** [LinkRequirements]' rule, at its edges: a lifetime measured from when the link was made, inclusive. */
class LinkRequirementsTest {

    private val made = Instant.parse("2026-10-01T10:00:00Z")

    @Test
    fun nothingRequiredAdmitsEveryLink() {
        assertNull(LinkRequirements.refusal(emptyMap(), hasPassword = false, made, expiresAt = null))
    }

    @Test
    fun aRequiredPasswordRefusesALinkWithoutOneAndNamesItBeforeTheEndDate() {
        val both = mapOf(LINK_PASSWORD to 1, LINK_MAX_DAYS to 7)
        assertEquals(Failures.linkPasswordRequired, LinkRequirements.refusal(both, hasPassword = false, made, expiresAt = null))
        assertNull(LinkRequirements.refusal(mapOf(LINK_PASSWORD to 1), hasPassword = true, made, expiresAt = null))
    }

    @Test
    fun missedNamesEveryRequirementALinkMissesThePasswordFirstAndTheRefusalIsItsFirst() {
        val both = mapOf(LINK_PASSWORD to 1, LINK_MAX_DAYS to 7)
        assertEquals(
            listOf(LinkRequirements.Missed.Password, LinkRequirements.Missed.Lifetime(7)),
            LinkRequirements.missed(both, hasPassword = false, made, expiresAt = null),
        )
        assertEquals(listOf(LinkRequirements.Missed.Lifetime(7)), LinkRequirements.missed(both, hasPassword = true, made, expiresAt = null))
        assertEquals(emptyList<LinkRequirements.Missed>(), LinkRequirements.missed(both, true, made, made.plus(Duration.ofDays(7))))
    }

    @Test
    fun aMaximumLifetimeCountsFromWhenTheLinkWasMadeAndIncludesItsLastInstant() {
        val rules = mapOf(LINK_MAX_DAYS to 7)
        assertEquals(Failures.linkExpiryRequired, LinkRequirements.refusal(rules, hasPassword = false, made, expiresAt = null))
        assertNull(LinkRequirements.refusal(rules, false, made, made.plus(Duration.ofDays(7))))
        assertEquals(
            Failures.linkExpiryRequired,
            LinkRequirements.refusal(rules, false, made, made.plus(Duration.ofDays(7)).plusMillis(1)),
        )
    }
}
