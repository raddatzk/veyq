package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements
import java.time.Duration
import java.time.Instant

/**
 * What a link must carry under the requirements on `core.public-shares` in effect for its tenant (ADR 0088 decision 8,
 * uc.admin.capability-requirements): a password while `linkPassword` is set, an end date at most `linkMaxDays` days after
 * the link was made while that is set. One rule for every role of link, and for every place that asks it: a new link
 * (made now), an edit (the link's own start) and the guest gate (what exists).
 *
 * **Measured from when the link was made**, never from now: a moving "within N days from now" would refuse a link today
 * and serve it again later, as its end date came closer. So a link older than N days cannot be fixed by an edit; it is
 * renewed, which makes a new link with a new start. The server sees only that a verifier exists, never the password.
 */
object LinkRequirements {

    /** The refusal a link with these properties meets, or null when it meets every requirement in effect. The password
     *  first, so a link that misses both names the one an edit can always fix. */
    fun refusal(requirements: Map<String, Int>, hasPassword: Boolean, createdAt: Instant, expiresAt: Instant?): HttpFailure? {
        if (requirements[CapabilityRequirements.LINK_PASSWORD] != null && !hasPassword) return Failures.linkPasswordRequired
        val maxDays = requirements[CapabilityRequirements.LINK_MAX_DAYS] ?: return null
        if (expiresAt == null || expiresAt.isAfter(createdAt.plus(Duration.ofDays(maxDays.toLong())))) return Failures.linkExpiryRequired
        return null
    }

    /** Whether an existing [share] meets them — the guest gate's question; a link that does not answers as unknown. */
    fun admits(requirements: Map<String, Int>, share: ShareEntity): Boolean =
        refusal(requirements, share.pwVerifier != null, share.createdAt, share.expiresAt) == null
}
