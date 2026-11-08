package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.ShareGuestStop
import me.raddatz.veyq.mobile.api.models.ShareRole
import me.raddatz.veyq.mobile.api.models.ShareSummary
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Purposes
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A renamed node's links (the web's `resealShareNames`): which link is open, and `K_share` recovered from its recovery
 * copy — its value key under the scope key, `K_share` under it, bound to (scope, node, share, the copy's epoch) — so a
 * copy moved onto another share, relabelled to another epoch, or wrapped under another key opens nothing.
 */
class ShareNamesTest {
    private val suite = V1(BouncyCastlePrimitives)
    private val joins = JoinRecords(suite)
    private val scope = "7e000000-0000-4000-8000-000000000001"
    private val node = "0e000000-0000-4000-9000-000000000001"
    private val shareA = "A".repeat(43)
    private val shareB = "B".repeat(43)
    private val scopeKey = ByteArray(32) { 3 }
    private val kShare = ByteArray(32) { (it * 7).toByte() }

    private fun share(id: String = shareA, enabled: Boolean = true, expiresAt: String? = null) = ShareSummary(
        shareId = id, nodeId = node, scopeId = scope, role = ShareRole.READ, sealedName = "", enabled = enabled, mine = true,
        reissueRequired = false, deletable = true, newGuestsRefused = false, writeVerifierMissing = false,
        guestStops = emptyList<ShareGuestStop>(), createdAt = "2026-11-01T00:00:00Z", expiresAt = expiresAt,
    )

    /** A recovery copy as a mint writes it: for [forShare] at [epoch], its value key wrapped under [key] at [scopeEpoch]. */
    private fun copy(forShare: String = shareA, epoch: Long = 2, key: ByteArray = scopeKey, scopeEpoch: Long = 5): Pair<String, String> {
        val context = Context("share-key-recovery", "scopeId" to scope, "nodeId" to node, "shareId" to forShare, "epoch" to epoch)
        val valueKey = ByteArray(32) { 9 }
        val wrapped = suite.seal(joins.valueKeyContext(scope, scopeEpoch, joins.valueAddress(Purposes.SEAL, context)), key, valueKey)
        return b64(wrapped) to b64(suite.seal(context, valueKey, kShare))
    }

    private fun recover(c: Pair<String, String>, s: ShareSummary = share(), copyEpoch: Int = 2, scopeEpoch: Long = 5) =
        ShareNames.recoverShareKey(suite, joins, s, copyEpoch, scopeKey, scopeEpoch, c.first, c.second)

    @Test
    fun aLinksKeyComesBackFromItsRecoveryCopy() {
        assertContentEquals(kShare, recover(copy()))
    }

    @Test
    fun aCopyBoundElsewhereOpensNothing() {
        // Moved onto another share's row, relabelled to another epoch, or wrapped under another key or scope epoch.
        assertNull(recover(copy(forShare = shareB)))
        assertNull(recover(copy(), copyEpoch = 3))
        assertNull(recover(copy(key = ByteArray(32) { 4 })))
        assertNull(recover(copy(), scopeEpoch = 6))
    }

    @Test
    fun onlyAnOpenLinkIsResealed() {
        val now = kotlin.time.Instant.parse("2026-11-08T12:00:00Z").toEpochMilliseconds()
        assertEquals(true, ShareNames.isOpen(share(), now))
        assertEquals(true, ShareNames.isOpen(share(expiresAt = "2026-11-09T00:00:00Z"), now))
        assertEquals(false, ShareNames.isOpen(share(expiresAt = "2026-11-08T11:59:59Z"), now))
        assertEquals(false, ShareNames.isOpen(share(enabled = false), now))
    }
}
