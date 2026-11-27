package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.apis.ModerationApi
import me.raddatz.veyq.mobile.api.models.ModerationEpochGrant
import me.raddatz.veyq.mobile.api.models.ServerOwnerKeyDelivery
import me.raddatz.veyq.mobile.api.models.ServerOwnerKeyDeliveryUpload
import me.raddatz.veyq.mobile.api.models.ServerOwnerKeyGrant
import me.raddatz.veyq.mobile.api.models.StagedModerationGrant
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.XWingKey
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * The instance moderation key as an account holds it (ADR 0099 §§ 3–5, inbox #965): one `moderation-grant` [epoch] seal of
 * each live epoch's `M_n` to the account's identity. The core never keeps an `M_n`: it opens one only to re-seal it — to
 * the new identity when the account replaces its keys (the web's `ModerationKeyStage`), or to the Server Owner when the
 * last holder erases the account (the web's `sealForServerOwner`) — after checking it against the epoch's published
 * bundle, and zeroes it at once. The core moderates nothing (no case is opened here).
 */
internal object ModerationGrants {
    fun context(epoch: Int) = Context("moderation-grant", "epoch" to epoch.toLong())

    /** The live epochs this account holds a grant of, as the server serves them now; none for a non-moderator. */
    suspend fun read(account: VeyqAccount): List<ModerationEpochGrant> {
        val r = account.respond { ModerationApi(account.server, account.http).listMyModerationGrants() }
        if (r.status == 403 && account.errorCode(r) == "not_moderator") return emptyList()
        if (!r.success) throw VeyqAccount.Refused(r.status, account.errorCode(r))
        return r.body().epochs.filter { it.sealedGrant != null }
    }

    /**
     * [e]'s `M_n` opened with the outgoing identity's [outgoing] key and sealed to the new identity's encapsulation key
     * [nextKem] — or declared (`sealedGrant` null) where it does not open or the published bundle disowns it: it was already
     * closed to this account, and the publish deletes it. Never a throw: every byte here is the server's.
     */
    fun resealOrDeclare(suite: V1, outgoing: XWingKey, nextKem: ByteArray, e: ModerationEpochGrant): StagedModerationGrant {
        val declared = StagedModerationGrant(e.epoch, null)
        val sealed = e.sealedGrant ?: return declared
        val m = try {
            suite.openAs(context(e.epoch), outgoing, unb64(sealed))
        } catch (x: Exception) {
            return declared
        }
        return try {
            if (!suite.moderationBundle(m).same(unb64(e.kemPublicKey), unb64(e.signPublicKey))) declared
            else StagedModerationGrant(e.epoch, b64(suite.sealTo(context(e.epoch), nextKem, m)))
        } catch (x: Exception) {
            declared
        } finally {
            m.fill(0)
        }
    }

    /** A grant whose `M_n` the epoch's published bundle disowns: nothing is opened (the web's `ModerationBundleMismatch`). */
    class BundleMismatch(val epoch: Int) : Exception("moderation bundle mismatch: epoch $epoch")

    /**
     * Every grant of [epochs] opened with [kem] and checked against its published bundle (the web's `openGrants`); a grant
     * that does not open, or a mismatch, opens nothing (every key opened so far zeroed). The caller zeroes what it gets.
     */
    fun open(suite: V1, kem: XWingKey, epochs: List<ModerationEpochGrant>): Map<Int, ByteArray> {
        val opened = LinkedHashMap<Int, ByteArray>()
        try {
            for (e in epochs) {
                val sealed = e.sealedGrant ?: continue
                val m = suite.openAs(context(e.epoch), kem, unb64(sealed))
                if (!suite.moderationBundle(m).same(unb64(e.kemPublicKey), unb64(e.signPublicKey))) {
                    m.fill(0)
                    throw BundleMismatch(e.epoch)
                }
                opened[e.epoch] = m
            }
            return opened
        } catch (x: Exception) {
            for (m in opened.values) m.fill(0)
            throw x
        }
    }

    /** Why the last holder's hand-over could not be built (the web's `ServerOwnerDeliveryRefused`); nothing was sent. */
    class DeliveryRefused(val reason: Reason) : Exception("server owner delivery refused: $reason") {
        enum class Reason { UNANCHORED, NOT_HELD }
    }

    /**
     * The last holder's hand-over (ADR 0101 decision 9, the web's `sealForServerOwner`): each epoch [delivery] names, of
     * [opened], sealed to the Server Owner's identity. Refused [DeliveryRefused.Reason.NOT_HELD] where an epoch is not held.
     * The Server Owner's key is anchored by the caller first.
     */
    fun forServerOwner(suite: V1, opened: Map<Int, ByteArray>, delivery: ServerOwnerKeyDelivery): ServerOwnerKeyDeliveryUpload {
        if (delivery.epochs.any { it !in opened }) throw DeliveryRefused(DeliveryRefused.Reason.NOT_HELD)
        val recipient = suite.deserializeIdentityPublic(unb64(delivery.identityPublicKey)).kem
        return ServerOwnerKeyDeliveryUpload(
            delivery.identityPublicKey,
            delivery.epochs.map { epoch -> ServerOwnerKeyGrant(epoch, b64(suite.sealTo(context(epoch), recipient, opened.getValue(epoch)))) },
        )
    }

    /**
     * The whole hand-over over [account]: the Server Owner anchored by the head the preview served (no tenant: ADR 0028
     * decision 2 — an anomaly or a refused pin store delivers nothing), this account's grants opened with its identity and
     * checked, each epoch sealed to them, every opened key zeroed.
     */
    suspend fun sealForServerOwner(account: VeyqAccount, delivery: ServerOwnerKeyDelivery): ServerOwnerKeyDeliveryUpload {
        val anchor = try {
            account.anchoringOfAccount.anchorIdentity(delivery.serverOwnerId, ServedIdentity(delivery.identityPublicKey, delivery.keyChainHead), null)
        } catch (e: TrustStateRefused) {
            throw DeliveryRefused(DeliveryRefused.Reason.UNANCHORED)
        }
        if (anchor == Anchor.ANOMALY) throw DeliveryRefused(DeliveryRefused.Reason.UNANCHORED)
        val grants = read(account)
        val suite = account.suite
        val identity = account.identityCopy()
        val kem = suite.xwingKey(identity.kemSeed)
        try {
            val opened = open(suite, kem, grants)
            try {
                return forServerOwner(suite, opened, delivery)
            } finally {
                for (m in opened.values) m.fill(0)
            }
        } finally {
            kem.mlKemDk.fill(0)
            kem.x25519Sk.fill(0)
            identity.kemSeed.fill(0)
            identity.signSeed.fill(0)
        }
    }
}

/**
 * The moderation key this account holds, staged for its key replacement (ADR 0099 § 4, the web's `ModerationKeyStage`):
 * every live epoch it holds a grant of opened with the OUTGOING identity, checked against the epoch's published bundle and
 * re-sealed to the new identity's encapsulation key [nextKem], or declared ([ModerationGrants.resealOrDeclare]) — so a sole
 * holder keeps the key through the publish. A non-moderator holds nothing and stages nothing. Mirrors what the server's
 * stage holds by the grant each epoch was judged from, so a re-read after `409 rotation_plan_changed` puts up only what
 * moved; a stage opened afresh starts it over. [send] is `putRotationStageModerationGrants`, at most [CHUNK] at a time.
 */
internal class ModerationKeyStage(
    private val suite: V1,
    private val outgoing: me.raddatz.veyq.mobile.crypto.IdentityPrivate,
    private val nextKem: ByteArray,
    private val send: suspend (stageId: String, grants: List<StagedModerationGrant>) -> Unit,
) {
    private var stageId = ""
    private val staged = HashMap<Int, String>()

    /** Whether [now] holds an epoch the stage [stageId] does not hold as judged — something to stage again. */
    fun moved(stageId: String, now: List<ModerationEpochGrant>): Boolean =
        if (stageId != this.stageId) now.isNotEmpty() else now.any { staged[it.epoch] != it.sealedGrant }

    /** Every epoch of [now] the stage [stageId] does not hold as judged, re-sealed or declared, in chunks of [CHUNK]. */
    suspend fun put(stageId: String, now: List<ModerationEpochGrant>) {
        if (stageId != this.stageId) {
            this.stageId = stageId
            staged.clear()
        }
        val todo = now.filter { it.sealedGrant != null && staged[it.epoch] != it.sealedGrant }
        if (todo.isEmpty()) return
        val kem = suite.xwingKey(outgoing.kemSeed.copyOf())
        try {
            for (chunk in todo.chunked(CHUNK)) {
                send(stageId, chunk.map { ModerationGrants.resealOrDeclare(suite, kem, nextKem, it) })
                for (e in chunk) staged[e.epoch] = e.sealedGrant!!
            }
        } finally {
            kem.seed.fill(0)
            kem.mlKemDk.fill(0)
            kem.x25519Sk.fill(0)
        }
    }

    companion object {
        /** The contract's `maxItems`, the web's `STAGE_CHUNK`. */
        const val CHUNK = 256
    }
}
