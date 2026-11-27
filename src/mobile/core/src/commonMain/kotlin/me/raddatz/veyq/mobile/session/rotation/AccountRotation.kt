package me.raddatz.veyq.mobile.session.rotation

import kotlinx.coroutines.CancellationException
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.apis.AccountApi
import me.raddatz.veyq.mobile.api.apis.AccountRotationApi
import me.raddatz.veyq.mobile.api.apis.FavoritesApi
import me.raddatz.veyq.mobile.api.models.RotationStageModerationGrants
import me.raddatz.veyq.mobile.session.ModerationGrants
import me.raddatz.veyq.mobile.session.ModerationKeyStage
import me.raddatz.veyq.mobile.api.apis.TenantsApi
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.KeyChainStepSignatures
import me.raddatz.veyq.mobile.api.models.MemberVerification
import me.raddatz.veyq.mobile.api.models.OpenRotationStageRequest
import me.raddatz.veyq.mobile.api.models.PutFavoritesRequest
import me.raddatz.veyq.mobile.api.models.PutVerificationRequest
import me.raddatz.veyq.mobile.api.models.RotateAccountKeysRequest
import me.raddatz.veyq.mobile.api.models.RotatedWrap
import me.raddatz.veyq.mobile.api.models.RotationPlanInvite
import me.raddatz.veyq.mobile.api.models.RotationPlanJoinRecord
import me.raddatz.veyq.mobile.api.models.RotationPlanScope
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.api.models.RotationStageGrants
import me.raddatz.veyq.mobile.api.models.RotationStageInvites
import me.raddatz.veyq.mobile.api.models.RotationStageJoinEndorsements
import me.raddatz.veyq.mobile.api.models.StagedInviteResign
import me.raddatz.veyq.mobile.api.models.StagedJoinEndorsement
import me.raddatz.veyq.mobile.api.models.StagedSelfGrant
import me.raddatz.veyq.mobile.crypto.AccountProofs
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.Verifications
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.ColdCredential
import me.raddatz.veyq.mobile.session.GrantOutcome
import me.raddatz.veyq.mobile.session.TrustStateRefused
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.words.Msg

/**
 * The core's port of the web's `rotateKeys.ts` (uc.account.rotate-keys, ADR 0060, ADR 0111 decisions 6–13 and 20–22, ADR
 * 0148 decision 7), step for step, over the signed-in [VeyqAccount]:
 *
 *  1. **Mint**, in memory: the cold proof, `MK'`, the new identity, the wraps `PASSWORD` = Argon2id(lp(SK') ‖ lp(pw'))
 *     and `RECOVERY` = Argon2id(SK') under fresh salts, the new proof key, the attestation of the auth key (the old auth
 *     key signs the new — the same key when the password is kept), and the key-chain step old → new, signed by the old
 *     identity AND the old proof key.
 *  2. **Stage**: every scope of the plan, its current epoch key opened with the OLD identity, granted to the NEW one and
 *     signed by it — a grant this device refuses declared in `unreadableScopeIds`, never dropped —, in chunks of
 *     [STAGE_CHUNK]; the open invites this account signed re-signed (or declared) and the join records it rooted
 *     endorsed (or declared). The moderation key this account holds is re-sealed to the new identity or declared, every live epoch ([ModerationKeyStage], #965).
 *  3. **Publish**: one request naming the stage and its digest under the proof statement, the pin store and the device
 *     register re-sealed under `MK'` riding inside it; `409 stale_revision` re-seals, `409 rotation_plan_changed`
 *     re-stages the delta, both bounded by [MAX_PUBLISH_ATTEMPTS]; a declared refusal wrote nothing; any other failure
 *     reads the account back — committed, not committed, or unknown (the account closed: [OutcomeUnknown]).
 *  4. **After**: the account takes the new keys at once ([VeyqAccount.swapKeys]: the offline store wiped and refilled,
 *     ADR 0140 decision 2); then, best effort, this device's marks carried to the new key's slot, the node pins
 *     re-sealed, each application's Master-Key material ([AppKinds.masterKeyRotated]), this account's verifications
 *     re-signed by the new identity, and [finishRotation]. Nothing after the commit throws.
 *
 * Every secret — both Master Keys, both identities, both Secret Keys, every derived key — is zeroed in a `finally`.
 */
internal class AccountRotation(
    private val account: VeyqAccount,
    /** The spaces the host has open, by their names (a group's name is known only where a tenant opened it). */
    private val spaces: suspend () -> List<me.raddatz.veyq.mobile.navigation.SpaceRef> = { emptyList() },
    /** A node of the tenant the host has open, as its share dialog opens it; null where that tenant does not hold it. */
    private val node: suspend (scopeId: String, nodeId: String) -> me.raddatz.veyq.mobile.navigation.NodeAt? = { _, _ -> null },
) : KeyRotation.Engine {
    private val p = account.primitives
    private val suite = account.suite
    private val proofs = AccountProofs(p)
    private val trust = Trust(suite)
    private val joins = JoinRecords(suite)
    private val verifications = Verifications(suite)
    private fun api() = AccountRotationApi(account.server, account.http)


    override suspend fun prove(password: String, secretKey: ByteArray): ColdCredential.Refusal? = try {
        ColdCredential.prove(suite, account.masterKeyCopy(), account.keyMaterialNow(), password, secretKey).fill(0)
        null
    } catch (e: ColdCredential.Refused) {
        e.reason
    }

    /** `resumeCommitted`: the floor opened to the account's current Master Key; the account takes it and finishes. */
    override suspend fun resume(password: String, secretKey: ByteArray): List<RotationScopeState> {
        val material = account.keyMaterialNow()
        val floor = ColdCredential.openFloor(suite, material, password, secretKey)
        val identity = try {
            suite.deserializeIdentityPrivate(suite.open(Context("identity-priv"), floor.masterKey, unb64(material.wrappedIdentityPrivateKey)))
        } catch (e: Exception) {
            floor.masterKey.fill(0)
            throw e
        }
        account.swapKeys(floor.masterKey, identity, material)
        return runCatching { account.finishRotation() }.getOrElse { if (it is CancellationException) throw it else emptyList() }
    }

    /**
     * The web's `scopeLabel` over what this device holds: a tenant root by its tenant's name (its scope id is the tenant
     * id), a personal space by the plan's kind, a space the open tenant lists by its name. Read after the commit — the plan
     * still lists every scope of the account; a read that fails names fewer.
     */
    override suspend fun names(): KeyRotation.ScopeNames {
        val plan = runCatching { plan() }.getOrElse { if (it is CancellationException) throw it else emptyList() }
        val open = runCatching { spaces() }.getOrElse { if (it is CancellationException) throw it else emptyList() }
        val named = LinkedHashMap<String, String>()
        for (s in open) if (s.kind != "PERSONAL" && s.name.isNotBlank()) named[s.scopeId] = s.name
        for (t in account.tenants) named[t.tenantId] = t.name
        val personal = plan.filter { it.kind == RotationPlanScope.Kind.PERSONAL }.map { it.scopeId }.toSet() +
            open.filter { it.kind == "PERSONAL" }.map { it.scopeId }
        return KeyRotation.ScopeNames(personal, named)
    }

    /**
     * The web's `listLinksToRenew`: the person's own links (`listShares()` without a node, account-addressed: every
     * tenant's) the server marks `reissueRequired`; each named from the open tenant's tree where it holds the node, else
     * by its tenant (the plan's) — never a key read for it.
     */
    override suspend fun linksToRenew(names: KeyRotation.ScopeNames): List<KeyRotation.RenewLink> {
        val mine = account.call { me.raddatz.veyq.mobile.api.apis.SharingApi(account.server, account.http).listShares() }
            .filter { it.mine && it.reissueRequired }
        if (mine.isEmpty()) return emptyList()
        val tenantOf = runCatching { plan() }.getOrElse { if (it is CancellationException) throw it else emptyList() }
            .associate { it.scopeId to it.tenantId }
        return mine.map { share ->
            val at = runCatching { node(share.scopeId, share.nodeId) }.getOrElse { if (it is CancellationException) throw it else null }
            val tenant = if (at != null) null else tenantOf[share.scopeId]?.let { t -> account.tenants.firstOrNull { it.tenantId == t }?.name }
            KeyRotation.named(
                KeyRotation.RenewLink(
                    share.shareId, share.scopeId, at, share.role == me.raddatz.veyq.mobile.api.models.ShareRole.WRITE_ONLY, tenant,
                ),
                names,
            )
        }
    }

    override suspend fun publish(password: String, secretKey: ByteArray, newSecretKey: ByteArray, newPassword: String?): KeyRotation.Published {
        require(newSecretKey.size == 32) { "the new Secret Key is 32 bytes" }
        val oldMk = account.masterKeyCopy() ?: throw KeyRotation.Refused(Msg.ROTATION_ERROR_FAILED)
        val current = account.identityCopy()
        val identity = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        val newMk = p.randomBytes(32)
        val oldProofSeed = proofs.secretKeyProofSeed(secretKey)
        val newProofSeed = proofs.secretKeyProofSeed(newSecretKey)
        val seeds = mutableListOf<ByteArray>()
        val carried = mutableListOf<Carried>()
        var committed = false
        try {
            // the cold gate once more, against the floor as served now: a stale vault resumes instead of minting
            val proven = prove(password, secretKey)
            if (proven == ColdCredential.Refusal.STALE) return KeyRotation.Published(true, resume(password, secretKey), emptyList())
            if (proven != null) throw KeyRotation.Refused(if (proven == ColdCredential.Refusal.NO_FLOOR) Msg.ROTATION_ERROR_NO_FLOOR else Msg.ROTATION_ERROR_CREDENTIAL)

            val currentPublic = account.myPublic
            val newPub = suite.identityPublic(identity)
            val newPublic = newPub.serialize()
            val newPublicB64 = b64(newPublic)
            val oldProofPublic = p.ed25519Public(oldProofSeed)
            val newProofPublic = p.ed25519Public(newProofSeed)

            // the key-chain step: the served chain walked must end at this account's keys now
            val step = nextChainStep(Trust.KeyChainKeys(currentPublic, oldProofPublic), Trust.KeyChainKeys(newPublic, newProofPublic))
            val stepIdentitySignature = p.ed25519Sign(current.signSeed, step)
            val stepProofSignature = p.ed25519Sign(oldProofSeed, step)

            val wrappedPriv = suite.seal(Context("identity-priv"), newMk, concat(lp(identity.kemSeed), lp(identity.signSeed)))
            val nextPw = newPassword ?: password
            val pwSalt = p.randomBytes(16)
            val recSalt = p.randomBytes(16)
            val pwKek = suite.kekPasswordSecret(newSecretKey, nextPw, pwSalt)
            val recKek = suite.kekRecovery(newSecretKey, recSalt)
            val passwordWrap = AccountProofs.ProofWrap(suite.seal(Context("master-key"), pwKek, newMk), suite.encodeKdfParams(V1.ARGON, pwSalt))
            val recoveryWrap = AccountProofs.ProofWrap(suite.seal(Context("master-key"), recKek, newMk), suite.encodeKdfParams(V1.ARGON, recSalt))
            pwKek.fill(0)
            recKek.fill(0)

            // the password half of the gate: the current auth key signs the one the account keeps
            val challenge = account.challenge(account.email)
            val authSalt = suite.authSalt(unb64(challenge.instanceSalt), account.email)
            val currentAuthSeed = suite.authSeed(password, authSalt).also { seeds += it }
            val nextAuthSeed = if (nextPw == password) currentAuthSeed else suite.authSeed(nextPw, authSalt).also { seeds += it }
            val currentAuth = p.ed25519Public(currentAuthSeed)
            val nextAuth = p.ed25519Public(nextAuthSeed)
            val attestation = proofs.passwordChangeAttestation(currentAuthSeed, nextAuth)

            val stage = SelfGrantStage(identity, newPublic)
            val rooted = RootedStage(current, identity)
            val moderationKey = ModerationKeyStage(suite, current, newPub.kem) { id, grants ->
                val r = account.respond { api().putRotationStageModerationGrants(id, RotationStageModerationGrants(grants)) }
                if (!r.success) throw VeyqAccount.Refused(r.status, account.errorCode(r))
            }
            var plan = plan()
            stage.stageAll(plan)
            stageRooted(stage, rooted, moderationKey, plan)
            // the outgoing key's verifications, read NOW while it is still the account's (ADR 0111 decision 13)
            carried += collectMyVerifications(plan)

            for (attempt in 1..MAX_PUBLISH_ATTEMPTS) {
                // the pin store and the register ride inside the publish, re-sealed under MK'; a refused one refuses (E8)
                val pins = try { account.resealedPins(newMk) } catch (e: TrustStateRefused) { throw KeyRotation.Refused(Msg.ROTATION_ERROR_TRUST_STATE) }
                val register = try { account.resealedRegister(newMk) } catch (e: TrustStateRefused) { throw KeyRotation.Refused(Msg.ROTATION_ERROR_TRUST_STATE) }
                val digest = stage.digest()
                val statement = proofs.rotationProofStatement(
                    currentPublic, newPublic, wrappedPriv, passwordWrap, recoveryWrap, step, currentAuth, nextAuth, newProofPublic,
                    stage.id, digest,
                )
                val request = RotateAccountKeysRequest(
                    identityPublicKey = newPublicB64,
                    wrappedIdentityPrivateKey = b64(wrappedPriv),
                    passwordWrap = RotatedWrap(b64(passwordWrap.wrappedKey), b64(passwordWrap.kdfParams)),
                    recoveryWrap = RotatedWrap(b64(recoveryWrap.wrappedKey), b64(recoveryWrap.kdfParams)),
                    secretKeyProofSignature = b64(p.ed25519Sign(oldProofSeed, statement)),
                    identityPins = pins,
                    deviceRegister = register,
                    // the kit this ceremony prints, recorded under MK' as the web's rotation does (ADR 0157 decision 4)
                    kitRecord = me.raddatz.veyq.mobile.account.KitRecord.seal(suite, newMk),
                    keyChainStep = KeyChainStepSignatures(b64(stepIdentitySignature), b64(stepProofSignature)),
                    stageId = stage.id,
                    stagedGrantsDigest = b64(digest),
                    unreadableScopeIds = stage.unreadableIds(),
                    authPublicKey = b64(nextAuth),
                    attestationSignature = b64(attestation),
                    secretKeyProofPublicKey = b64(newProofPublic),
                    secretKeyProofPossession = b64(p.ed25519Sign(newProofSeed, statement)),
                )
                val r = try {
                    account.respond { api().rotateAccountKeys(request) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (r != null && r.success) {
                    committed = true
                    afterCommit(oldMk, newMk, identity, pins.revision ?: 0, register.revision ?: 0, stage.planScopeIds(), carried, step)
                    break
                }
                val code = r?.let { account.errorCode(it) }
                val retry = attempt < MAX_PUBLISH_ATTEMPTS
                if (r != null && retry && r.status == 409 && code == "stale_revision") continue
                if (r != null && retry && r.status == 409 && code == "rotation_plan_changed") {
                    plan = plan()
                    stage.restage(plan, rooted.moved(stage.id, rooted.read()) || moderationKey.moved(stage.id, ModerationGrants.read(account)))
                    stageRooted(stage, rooted, moderationKey, plan)
                    continue
                }
                if (r != null && r.status == 413) throw KeyRotation.Refused(Msg.ROTATION_ERROR_TOO_LARGE)
                if (r != null && declared(r.status, code)) throw KeyRotation.Refused(refusalOf(r.status))
                when (readOutcome(newMk, newPublic, oldMk, currentPublic)) {
                    Outcome.COMMITTED -> {
                        committed = true
                        afterCommit(oldMk, newMk, identity, pins.revision ?: 0, register.revision ?: 0, stage.planScopeIds(), carried, step)
                    }
                    Outcome.NOT_COMMITTED -> throw KeyRotation.Refused(Msg.ROTATION_ERROR_FAILED)
                    Outcome.UNKNOWN -> {
                        // neither identity can be held with certainty: the account closes, the person unlocks again
                        runCatching { account.retire() }
                        throw KeyRotation.OutcomeUnknown(null)
                    }
                }
                break
            }
            if (!committed) throw KeyRotation.Refused(Msg.ROTATION_ERROR_FAILED)
            val states = runCatching { account.finishRotation() }.getOrElse { if (it is CancellationException) throw it else emptyList() }
            return KeyRotation.Published(false, states, stage.unreadableIds())
        } finally {
            oldMk.fill(0)
            current.kemSeed.fill(0)
            current.signSeed.fill(0)
            // the new identity and MK' were handed to the account at the commit (swapKeys copies nothing): only an
            // uncommitted run zeroes them here
            if (!committed) {
                identity.kemSeed.fill(0)
                identity.signSeed.fill(0)
                newMk.fill(0)
            }
            oldProofSeed.fill(0)
            newProofSeed.fill(0)
            for (s in seeds) s.fill(0)
            for (c in carried) c.key.fill(0)
        }
    }

    // ---- the key chain ----

    /** The step this rotation appends: the served chain, walked, must end at exactly the keys this account holds now. */
    private suspend fun nextChainStep(current: Trust.KeyChainKeys, installed: Trust.KeyChainKeys): ByteArray {
        val chain = account.keyChainOf(account.userId) ?: throw IllegalStateException("rotation: the server serves no key chain for this account")
        val links = account.anchoringOfAccount.links(chain) ?: throw IllegalStateException("rotation: the served key chain does not decode")
        val walked = trust.verifyKeyChain(account.userId, links, null) as? Trust.KeyChainVerdict.Ok
            ?: throw IllegalStateException("rotation: the served key chain does not verify")
        if (!walked.keys.identityPublicKey.contentEquals(current.identityPublicKey) ||
            !walked.keys.secretKeyProofPublicKey.contentEquals(current.secretKeyProofPublicKey)
        ) throw IllegalStateException("rotation: the served key chain does not end at this account's keys")
        return trust.keyChainStepStatement(account.userId, walked.index + 1L, walked.head, current, installed)
    }

    // ---- the stage ----

    private suspend fun plan(): List<RotationPlanScope> = account.call { api().getAccountRotationPlan() }

    /**
     * The self-grants of one rotation and the server's stage that holds them: [staged] mirrors exactly what the server
     * holds (the digest the publish names is over it), [unreadable] the scopes declared instead.
     */
    private inner class SelfGrantStage(private val identity: IdentityPrivate, private val newPublic: ByteArray) {
        var id = ""
        private val staged = LinkedHashMap<String, Pair<Long, String>>()
        private val unreadable = LinkedHashSet<String>()
        private var plan: List<RotationPlanScope> = emptyList()

        suspend fun stageAll(plan: List<RotationPlanScope>) {
            this.plan = plan
            id = account.call { api().openRotationStage(OpenRotationStageRequest(b64(newPublic))) }.stageId
            staged.clear()
            unreadable.clear()
            put(plan)
        }

        /** After `409 rotation_plan_changed`: only what moved; nothing moved (and nothing rooted) — the stage is gone. */
        suspend fun restage(plan: List<RotationPlanScope>, rootedMoved: Boolean) {
            val delta = plan.filter { staged[it.scopeId]?.first != it.currentEpoch.toLong() }
            if (delta.isEmpty() && rootedMoved) {
                this.plan = plan
                return
            }
            if (delta.isEmpty()) return stageAll(plan)
            this.plan = plan
            try {
                put(delta)
            } catch (e: VeyqAccount.Refused) {
                if (e.status == 404 && e.code == "rotation_stage_not_found") stageAll(plan) else throw e
            }
        }

        fun digest(): ByteArray = proofs.rotationStageDigest(staged.map { (scopeId, g) -> AccountProofs.StageEntry(scopeId, g.first, unb64(g.second)) })

        fun unreadableIds(): List<String> = plan.map { it.scopeId }.filter { it in unreadable }

        fun planScopeIds(): List<String> = plan.map { it.scopeId }

        private suspend fun put(rows: List<RotationPlanScope>) {
            val grants = rows.mapNotNull { selfGrant(it) }
            for (chunk in grants.chunked(STAGE_CHUNK)) {
                val r = account.respond { api().putRotationStageGrants(id, RotationStageGrants(chunk)) }
                if (!r.success) throw VeyqAccount.Refused(r.status, account.errorCode(r))
                for (g in chunk) staged[g.scopeId] = (g.epoch ?: 0).toLong() to g.wrappedGrant
            }
        }

        /** The scope's current epoch key, opened with the OLD identity (never a lagging grant), granted to the NEW one. */
        private suspend fun selfGrant(row: RotationPlanScope): StagedSelfGrant? {
            val key = openOrDeclare(row)
            if (key == null) {
                unreadable += row.scopeId
                return null
            }
            unreadable -= row.scopeId
            try {
                val epoch = row.currentEpoch.toLong()
                val wrapped = suite.sealTo(Context("scope-grant", "scopeId" to row.scopeId, "epoch" to epoch), suite.identityPublic(identity).kem, key)
                val statement = suite.grantStatement(row.scopeId, row.kind.value, epoch, account.userId, newPublic, wrapped)
                return StagedSelfGrant(row.scopeId, row.currentEpoch, b64(wrapped), b64(p.ed25519Sign(identity.signSeed, statement)), b64(p.sha256(newPublic)))
            } finally {
                key.fill(0)
            }
        }
    }

    /** The row's current epoch key as this device opens it with the account's identity, or null: declared unreadable. */
    private suspend fun openOrDeclare(row: RotationPlanScope): ByteArray? {
        if (row.grantedEpoch < row.currentEpoch) return null
        return when (val o = account.openOwnGrant(summaryOf(row), null, lagging = false)) {
            is GrantOutcome.Opened -> o.key
            is GrantOutcome.Refused -> null
        }
    }

    private class Rooted(val invites: List<RotationPlanInvite>, val records: List<RotationPlanJoinRecord>)

    /**
     * What this account rooted, re-signed by the NEW identity into the stage (ADR 0111 decisions 20 and 21): an open invite
     * whose two signatures are the outgoing identity's, re-signed; a join record it rooted (opened under the tenant key,
     * verifying, naming this account, signed by or endorsed under the outgoing key) endorsed; anything else declared.
     */
    private inner class RootedStage(private val outgoing: IdentityPrivate, private val next: IdentityPrivate) {
        private var stageId = ""
        private val invites = HashMap<String, String>()
        private val records = HashMap<String, String>()
        private val outgoingSign = p.ed25519Public(outgoing.signSeed)

        suspend fun read(): Rooted = Rooted(account.call { api().getRotationPlanInvites() }, account.call { api().getRotationPlanJoinRecords() })

        fun moved(stageId: String, now: Rooted): Boolean =
            stageId != this.stageId || now.invites.any { invites[it.inviteId] != mark(it) } || now.records.any { records[key(it)] != mark(it) }

        suspend fun put(stageId: String, plan: List<RotationPlanScope>, now: Rooted) {
            if (stageId != this.stageId) {
                this.stageId = stageId
                invites.clear()
                records.clear()
            }
            for (chunk in now.invites.filter { invites[it.inviteId] != mark(it) }.chunked(STAGE_CHUNK)) {
                val r = account.respond { api().putRotationStageInvites(stageId, RotationStageInvites(chunk.map { resignOrDeclare(it) })) }
                if (!r.success) throw VeyqAccount.Refused(r.status, account.errorCode(r))
                for (i in chunk) invites[i.inviteId] = mark(i)
            }
            val todo = now.records.filter { records[key(it)] != mark(it) }
            if (todo.isEmpty()) return
            val staged = endorseOrDeclare(todo, plan)
            for ((i, chunk) in staged.chunked(STAGE_CHUNK).withIndex()) {
                val r = account.respond { api().putRotationStageJoinEndorsements(stageId, RotationStageJoinEndorsements(chunk)) }
                if (!r.success) throw VeyqAccount.Refused(r.status, account.errorCode(r))
                for (rec in todo.subList(i * STAGE_CHUNK, i * STAGE_CHUNK + chunk.size)) records[key(rec)] = mark(rec)
            }
        }

        @OptIn(kotlin.time.ExperimentalTime::class)
        private fun resignOrDeclare(i: RotationPlanInvite): StagedInviteResign {
            val declared = StagedInviteResign(i.inviteId, i.anchorEpoch, null, null)
            return try {
                val expires = kotlin.time.Instant.parse(i.expiresAt).epochSeconds
                val invitePublic = unb64(i.invitePublicKey)
                val keyStatement = joins.inviteKeyStatement(i.tenantId, i.inviteId, invitePublic, expires)
                val wrapped = unb64(i.anchorWrappedGrant)
                val grantStatement = suite.grantStatement(i.tenantId, "TENANT", i.anchorEpoch.toLong(), i.inviteId, invitePublic, wrapped)
                val mine = p.ed25519Verify(outgoingSign, keyStatement, unb64(i.inviteKeySignature)) &&
                    p.ed25519Verify(outgoingSign, grantStatement, unb64(i.anchorGrantSignature))
                if (!mine) declared
                else StagedInviteResign(i.inviteId, i.anchorEpoch, b64(p.ed25519Sign(next.signSeed, keyStatement)), b64(p.ed25519Sign(next.signSeed, grantStatement)))
            } catch (e: Exception) {
                declared
            }
        }

        private suspend fun endorseOrDeclare(todo: List<RotationPlanJoinRecord>, plan: List<RotationPlanScope>): List<StagedJoinEndorsement> {
            val out = HashMap<String, StagedJoinEndorsement>()
            for (tenantId in todo.map { it.tenantId }.distinct()) {
                val row = plan.firstOrNull { it.kind == RotationPlanScope.Kind.TENANT && it.scopeId == tenantId }
                val key = row?.let { runCatching { openOrDeclare(it) }.getOrNull() }
                try {
                    for (r in todo.filter { it.tenantId == tenantId }) {
                        val e = if (key == null) null else endorsement(r, key, row.currentEpoch.toLong())
                        out[key(r)] = StagedJoinEndorsement(tenantId, r.userId, e?.first, e?.second)
                    }
                } finally {
                    key?.fill(0)
                }
            }
            return todo.map { out.getValue(key(it)) }
        }

        private fun endorsement(r: RotationPlanJoinRecord, tenantKey: ByteArray, epoch: Long): Pair<String, String>? = runCatching {
            val record = joins.open(r.tenantId, r.userId, tenantKey, epoch, unb64(r.wrappedJoinRecordValueKey), unb64(r.sealedJoinRecord)) ?: return null
            if (!joins.verifies(r.tenantId, r.userId, record) || record.signerUserId != account.userId) return null
            val encoded = joins.encode(record)
            val statement = joins.endorsementStatement(r.tenantId, r.userId, account.userId, encoded)
            val namesOutgoing = record.signerIdentityPublicKey.contentEquals(account.myPublic)
            val endorsedOutgoing = r.joinEndorsement?.let { runCatching { p.ed25519Verify(outgoingSign, statement, unb64(it)) }.getOrDefault(false) } == true
            if (!namesOutgoing && !endorsedOutgoing) return null
            b64(p.ed25519Sign(next.signSeed, statement)) to b64(p.sha256(encoded))
        }.getOrNull()

        private fun mark(i: RotationPlanInvite) =
            listOf(i.tenantId, i.invitePublicKey, i.expiresAt, i.inviteKeySignature, i.anchorEpoch, i.anchorWrappedGrant, i.anchorGrantSignature).joinToString("|")

        private fun key(r: RotationPlanJoinRecord) = "${r.tenantId}:${r.userId}"
        private fun mark(r: RotationPlanJoinRecord) = "${r.sealedJoinRecord}|${r.wrappedJoinRecordValueKey}|${r.joinEndorsement ?: ""}"
    }

    /** What the account rooted into [stage]'s stage; a stage the server lost re-opened, a moved list re-read, bounded. */
    private suspend fun stageRooted(stage: SelfGrantStage, rooted: RootedStage, moderationKey: ModerationKeyStage, plan: List<RotationPlanScope>) {
        for (attempt in 1..MAX_PUBLISH_ATTEMPTS) {
            try {
                rooted.put(stage.id, plan, rooted.read())
                moderationKey.put(stage.id, ModerationGrants.read(account))
                return
            } catch (e: VeyqAccount.Refused) {
                val retry = attempt < MAX_PUBLISH_ATTEMPTS
                if (retry && e.status == 404 && e.code == "rotation_stage_not_found") {
                    stage.stageAll(plan)
                    continue
                }
                if (retry && e.status == 409 && e.code == "rotation_plan_changed") continue
                throw e
            }
        }
    }

    // ---- the outcome of a publish that got no declared answer ----

    private enum class Outcome { COMMITTED, NOT_COMMITTED, UNKNOWN }

    private suspend fun readOutcome(newMk: ByteArray, newPublic: ByteArray, oldMk: ByteArray, oldPublic: ByteArray): Outcome {
        val wrapped = runCatching { account.call { AccountApi(account.server, account.http).getAccountKeyMaterial() }.wrappedIdentityPrivateKey }
            .getOrElse { if (it is CancellationException) throw it else return Outcome.UNKNOWN }
        if (opensTo(newMk, wrapped, newPublic)) return Outcome.COMMITTED
        if (opensTo(oldMk, wrapped, oldPublic)) return Outcome.NOT_COMMITTED
        return Outcome.UNKNOWN
    }

    private fun opensTo(mk: ByteArray, wrapped: String, bundle: ByteArray): Boolean = runCatching {
        val priv = suite.deserializeIdentityPrivate(suite.open(Context("identity-priv"), mk, unb64(wrapped)))
        try {
            suite.identityPublic(priv).serialize().contentEquals(bundle)
        } finally {
            priv.kemSeed.fill(0)
            priv.signSeed.fill(0)
        }
    }.getOrDefault(false)

    // ---- after the commit ----

    /**
     * The commit's bookkeeping. The account takes the new keys first ([VeyqAccount.swapKeys]: never back to the old
     * identity, the offline store wiped); then, each best effort: the marks carried to the new slot, the node pins
     * re-sealed per scope, the applications' Master-Key material, the verifications re-signed by the new identity.
     */
    private suspend fun afterCommit(
        oldMk: ByteArray, newMk: ByteArray, identity: IdentityPrivate, pinsRevision: Long, registerRevision: Long,
        scopeIds: List<String>, carried: List<Carried>, step: ByteArray,
    ) {
        val oldMarks = account.trustMarks
        val material = runCatching { account.keyMaterialNow() }.getOrNull()
            ?: AccountKeyMaterial(account.userId, AccountKeyMaterial.SuiteId.v1, emptyList(), b64(suite.seal(Context("identity-priv"), newMk, concat(lp(identity.kemSeed), lp(identity.signSeed)))))
        account.swapKeys(newMk, identity, material)
        // the kit this ceremony printed is the account's record now (ADR 0157 decision 4): no clearer kit to suggest
        account.kitStatus.remember()
        runCatching { oldMarks.carryInto(account.trustMarks, pinsRevision + 1, registerRevision + 1) }
        runCatching { resealFavorites(oldMk, newMk, scopeIds) }
        runCatching { AppKinds.masterKeyRotated(account, oldMk, newMk) }
        runCatching { resignVerifications(carried, trust.keyChainHead(step)) }
    }

    /** The node pins of every scope, opened under the old Master Key and sealed under the new one (`favorites`). */
    private suspend fun resealFavorites(oldMk: ByteArray, newMk: ByteArray, scopeIds: List<String>) {
        val plan = runCatching { plan() }.getOrDefault(emptyList()).associateBy { it.scopeId }
        for (scopeId in scopeIds) {
            runCatching {
                val base = account.originOfTenant(plan[scopeId]?.tenantId ?: return@runCatching)
                val ct = account.call { FavoritesApi(base, account.http).getFavorites(scopeId) }.ciphertext ?: return@runCatching
                val ctx = Context("favorites", "scopeId" to scopeId)
                val plain = runCatching { suite.open(ctx, oldMk, unb64(ct)) }.getOrNull() ?: return@runCatching
                account.respond { FavoritesApi(base, account.http).putFavorites(scopeId, PutFavoritesRequest(b64(suite.seal(ctx, newMk, plain)))) }
            }
        }
    }

    /** One verification this account made, carried across the rotation: re-signed after the commit. */
    private class Carried(val tenantId: String, val subjectUserId: String, val subjectHead: ByteArray, val epoch: Long, val key: ByteArray)

    /**
     * Before the publish: in each tenant this account's own verifications that verify under its CURRENT identity at its
     * current head — read now, while that key is still the account's. A tenant this device cannot open carries nothing.
     */
    private suspend fun collectMyVerifications(plan: List<RotationPlanScope>): List<Carried> {
        val out = mutableListOf<Carried>()
        val myHead = runCatching { account.keyChainOf(account.userId)?.head }.getOrNull() ?: return out
        for (row in plan.filter { it.kind == RotationPlanScope.Kind.TENANT && it.blocked == null }) {
            val key = runCatching { openOrDeclare(row) }.getOrNull() ?: continue
            try {
                val epoch = row.currentEpoch.toLong()
                for (v in listMine(row.tenantId)) {
                    val address = Verifications.Address(row.tenantId, account.userId, v.subjectUserId)
                    val plain = runCatching {
                        joins.openValueField(row.tenantId, epoch, key, verifications.context(address, v.epoch.toLong()), unb64(v.wrappedValueKey), unb64(v.sealedStatement))
                    }.getOrNull() ?: continue
                    val opened = verifications.decode(plain) ?: continue
                    if (b64(opened.verifierHead) != myHead || !verifications.verifies(address, opened, account.mySignPublic)) continue
                    out += Carried(row.tenantId, v.subjectUserId, opened.subjectHead, epoch, key.copyOf())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // this tenant carries nothing
            } finally {
                key.fill(0)
            }
        }
        return out
    }

    private suspend fun listMine(tenantId: String): List<MemberVerification> {
        val base = account.originOfTenant(tenantId)
        val out = mutableListOf<MemberVerification>()
        var after: String? = null
        while (true) {
            val page = account.call { TenantsApi(base, account.http).listVerifications(tenantId, null, account.userId, after, 1000) }
            out += page.verifications
            val last = page.verifications.lastOrNull()
            if (page.more != true || last == null) return out
            after = "${last.verifierUserId}:${last.subjectUserId}"
        }
    }

    /** After the commit: each carried verification re-signed by the NEW identity (the account's now), naming [newHead]. */
    private suspend fun resignVerifications(carried: List<Carried>, newHead: ByteArray) {
        for (c in carried) {
            runCatching {
                val address = Verifications.Address(c.tenantId, account.userId, c.subjectUserId)
                val signed = Verifications.Verification(c.subjectHead, newHead, account.identitySign(verifications.statement(address, c.subjectHead, newHead)))
                val sealed = joins.sealValueField(c.tenantId, c.epoch, c.key, verifications.context(address, c.epoch), verifications.encode(signed))
                val base = account.originOfTenant(c.tenantId)
                account.respond {
                    TenantsApi(base, account.http).putMyVerification(c.tenantId, c.subjectUserId, PutVerificationRequest(c.epoch.toInt(), b64(sealed.sealed), b64(sealed.wrappedValueKey)))
                }
            }
            c.key.fill(0)
        }
    }

    private fun declared(status: Int, code: String?): Boolean =
        status in setOf(400, 401, 403, 422) || (status == 409 && code in setOf("rotation_unavailable", "stale_revision", "rotation_plan_changed"))

    private fun refusalOf(status: Int): Msg = if (status == 403) Msg.ROTATION_ERROR_CREDENTIAL else Msg.ROTATION_ERROR_FAILED

    companion object {
        /** The publish's resubmits (`stale_revision`, `rotation_plan_changed`): the web's `MAX_PUBLISH_ATTEMPTS`. */
        const val MAX_PUBLISH_ATTEMPTS = 5

        /** At most this many entries per stage request (the contract's `maxItems`): the web's `STAGE_CHUNK`. */
        const val STAGE_CHUNK = 256
    }
}
