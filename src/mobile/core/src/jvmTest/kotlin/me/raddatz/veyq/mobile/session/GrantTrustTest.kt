package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.IdentityPinEntry
import me.raddatz.veyq.mobile.api.models.IdentityPinEntryPage
import me.raddatz.veyq.mobile.api.models.IdentityPins
import me.raddatz.veyq.mobile.api.models.IdentityPinsWrite
import me.raddatz.veyq.mobile.api.models.KeyChain
import me.raddatz.veyq.mobile.api.models.KeyChainStep
import me.raddatz.veyq.mobile.api.models.ScopeKind
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.ScopeSuccession
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Purposes
import me.raddatz.veyq.mobile.crypto.Trust
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.crypto.unhex
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The grant check of `Grants.kt` and `IdentityPins.kt` against a server this test plays, port of the web's
 * `grantTrust.test.ts`, `scopes.test.ts` (signature, succession) and `identityPins.test.ts` (rollback, digest): each
 * refusal is a case that must stay closed, beside the case that opens.
 */
class GrantTrustTest {
    private val p = BouncyCastlePrimitives
    private val suite = V1(p)
    private val trust = Trust(suite)

    /** The pin store and key chains as a server serves them; it counts what it was asked. */
    private class FakeServer : TrustApi {
        var revision = 0L
        var blob: String? = null
        val entries = sortedMapOf<String, String>()
        val chains = mutableMapOf<String, KeyChain>()
        var chainFetches = 0
        var writes = 0
        var beforeWrite: (suspend () -> Unit)? = null

        override suspend fun pinHead() = IdentityPins(revision, blob)
        override suspend fun pinEntries(after: String?, limit: Int) =
            IdentityPinEntryPage(entries.filterKeys { after == null || it > after }.map { (k, v) -> IdentityPinEntry(k, v) }, false)
        override suspend fun writePins(write: IdentityPinsWrite): Boolean {
            beforeWrite?.let { beforeWrite = null; it() }
            if (write.revision != revision) return false
            write.put.forEach { entries[it.pinId] = it.entry }
            write.remove.forEach { entries.remove(it) }
            revision++
            blob = write.head
            writes++
            return true
        }
        override suspend fun keyChain(userId: String): KeyChain? { chainFetches++; return chains[userId] }
    }

    /** An account whose keys rotate: step i's identity and proof key, and its chain as the server serves it. */
    private inner class Person(val userId: String = UUID.randomUUID().toString()) {
        val identities = mutableListOf(IdentityPrivate(p.randomBytes(32), p.randomBytes(32)))
        val proofs = mutableListOf(p.randomBytes(32))

        fun bundle(i: Int = identities.lastIndex) = suite.identityPublic(identities[i]).serialize()
        fun rotate() { identities += IdentityPrivate(p.randomBytes(32), p.randomBytes(32)); proofs += p.randomBytes(32) }

        fun chain(): KeyChain {
            val steps = mutableListOf<KeyChainStep>()
            var head = ByteArray(0)
            for (i in identities.indices) {
                val keys = Trust.KeyChainKeys(bundle(i), p.ed25519Public(proofs[i]))
                if (i == 0) {
                    head = trust.keyChainHead(trust.keyChainGenesisStatement(userId, keys))
                    steps += KeyChainStep(0, b64(keys.identityPublicKey), b64(keys.secretKeyProofPublicKey), "2026-11-08T00:00:00Z")
                } else {
                    val retired = Trust.KeyChainKeys(bundle(i - 1), p.ed25519Public(proofs[i - 1]))
                    val stmt = trust.keyChainStepStatement(userId, i.toLong(), head, retired, keys)
                    steps += KeyChainStep(
                        i, b64(keys.identityPublicKey), b64(keys.secretKeyProofPublicKey), "2026-11-08T00:00:00Z", b64(head),
                        b64(p.ed25519Sign(identities[i - 1].signSeed, stmt)), b64(p.ed25519Sign(proofs[i - 1], stmt)),
                    )
                    head = trust.keyChainHead(stmt)
                }
            }
            return KeyChain(userId, b64(head), steps)
        }
    }

    private val me = Person()
    private val myKem = suite.xwingKey(me.identities[0].kemSeed)
    private val server = FakeServer()
    private val tenantId = UUID.randomUUID().toString()
    private val masterKey = p.randomBytes(32)
    private val device = MemoryDeviceStore()
    private val successions = mutableMapOf<String, List<ScopeSuccession>>()

    private fun marks(store: DeviceStore = device) = TrustMarks(store, p, masterKey)
    private fun pins(m: TrustMarks = marks()) = PinStore(suite, trust, server, m, masterKey)

    private fun opener(m: TrustMarks = marks()): GrantOpener {
        val anchoring = Anchoring(suite, trust, server, pins(m)) { 1_760_000_000_000 }
        return GrantOpener(suite, trust, p, me.userId, me.bundle(), suite.identityPublic(me.identities[0]).sign, myKem, m, anchoring) { successions[it] }
    }

    /** A grant of [key] to me at ([scopeId], [epoch]), signed by [granter]'s identity [at] (its newest by default). */
    private fun grant(
        key: ByteArray, granter: Person, kind: ScopeKind = ScopeKind.TENANT, scopeId: String = UUID.randomUUID().toString(),
        epoch: Int = 0, at: Int = granter.identities.lastIndex, tamper: Boolean = false,
    ): ScopeSummary {
        val wrapped = suite.sealTo(Context("scope-grant", "scopeId" to scopeId, "epoch" to epoch.toLong()), myKem.publicKey, key)
        val stmt = suite.grantStatement(scopeId, kind.value, epoch.toLong(), me.userId, me.bundle(), wrapped)
        val sig = p.ed25519Sign(granter.identities[at].signSeed, stmt).also { if (tamper) it[0] = (it[0] + 1).toByte() }
        return ScopeSummary(
            id = scopeId, kind = kind, role = ScopeSummary.Role.MEMBER, tenantId = tenantId, nameEpoch = 0, iconEpoch = 0,
            currentEpoch = epoch, grantedEpoch = epoch, wrappedGrant = b64(wrapped),
            granterUserId = granter.userId, grantSignature = b64(sig), granterIdentityPublicKey = b64(granter.bundle(at)),
        )
    }

    private fun member(person: Person, role: ScopeMember.Role, joinRecord: String? = null, pendingConsent: Boolean? = null) = ScopeMember(
        userId = person.userId, username = "x", avatarRevision = 0, role = role, identityPublicKey = b64(person.bundle()),
        keyChainHead = person.chain().head, pending = false, pendingConsent = pendingConsent, sealedJoinRecord = joinRecord,
    )

    private fun reason(o: GrantOutcome) = assertIs<GrantOutcome.Refused>(o).reason

    private val tenantKey = p.randomBytes(32)
    private val joins = JoinRecords(suite)

    /** The tenant as this device reads it: [members] listed, their join records opened with the tenant key at epoch 0. */
    private fun view(vararg members: ScopeMember) =
        TenantView.of(tenantId, members.toList(), tenantKey, 0, suite, Anchoring(suite, trust, server, pins()) { 1_760_000_000_000 })

    /**
     * [joiner]'s membership as the server lists it: a join record [signer] rooted with its key [signerAt] (an invite key
     * the signer signed, which signs the record beside the joiner's key [joinerAt]), sealed under a value key wrapped
     * under the tenant key, and — with [endorsedBy] — the signer's endorsement by that key of theirs.
     */
    private fun joinedMember(
        joiner: Person, signer: Person, signerAt: Int = signer.identities.lastIndex, joinerAt: Int = joiner.identities.lastIndex,
        endorsedBy: Int? = null, breakInviteSignature: Boolean = false,
    ): ScopeMember {
        val invite = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        val invitePublic = suite.identityPublic(invite).serialize()
        val inviteId = UUID.randomUUID().toString()
        val expiresAt = 1_784_635_200L
        val inviteKeySignature = p.ed25519Sign(signer.identities[signerAt].signSeed, joins.inviteKeyStatement(tenantId, inviteId, invitePublic, expiresAt))
            .also { if (breakInviteSignature) it[0] = (it[0] + 1).toByte() }
        fun record(inviteSignature: ByteArray, identitySignature: ByteArray) = JoinRecords.JoinRecord(
            joiner.bundle(joinerAt), p.ed25519Public(joiner.proofs[joinerAt]), inviteId, invitePublic, inviteKeySignature, expiresAt,
            signer.userId, signer.bundle(signerAt), inviteSignature, identitySignature,
        )
        val stmt = joins.joinRecordStatement(tenantId, joiner.userId, record(ByteArray(64), ByteArray(64)))
        val rec = record(p.ed25519Sign(invite.signSeed, stmt), p.ed25519Sign(joiner.identities[joinerAt].signSeed, stmt))
        val ctx = joins.recordContext(tenantId, joiner.userId)
        val valueKey = p.randomBytes(32)
        val wrapped = suite.seal(joins.valueKeyContext(tenantId, 0, joins.valueAddress(Purposes.FIELD, ctx)), tenantKey, valueKey)
        val endorsement = endorsedBy?.let {
            p.ed25519Sign(signer.identities[it].signSeed, joins.endorsementStatement(tenantId, joiner.userId, signer.userId, joins.encode(rec)))
        }
        return member(joiner, ScopeMember.Role.MEMBER).copy(
            sealedJoinRecord = b64(suite.sealField(ctx, valueKey, joins.encode(rec))),
            wrappedJoinRecordValueKey = b64(wrapped),
            joinEndorsement = endorsement?.let(::b64),
        )
    }

    @Test
    fun aSelfGrantOpensAndIsHeld() = runTest {
        val m = marks()
        val s = grant(p.randomBytes(32), me, ScopeKind.PERSONAL, epoch = 3)
        assertIs<GrantOutcome.Opened>(opener(m).open(s, null))
        assertEquals(3, m.floor(s.id))
        assertNotNull(m.held(s.id))
        assertEquals(0, server.chainFetches, "a self grant reads no key chain")
    }

    @Test
    fun aGrantBelowTheFloorOrTheCurrentEpochWaits() = runTest {
        val m = marks()
        val scopeId = UUID.randomUUID().toString()
        opener(m).open(grant(p.randomBytes(32), me, ScopeKind.PERSONAL, scopeId, epoch = 3), null)
        assertEquals(GrantOutcome.Reason.AWAITING_CURRENT_KEY, reason(opener(m).open(grant(p.randomBytes(32), me, ScopeKind.PERSONAL, scopeId, epoch = 2), null)))
        val stale = grant(p.randomBytes(32), me, ScopeKind.PERSONAL, epoch = 1).copy(currentEpoch = 2)
        assertEquals(GrantOutcome.Reason.AWAITING_CURRENT_KEY, reason(opener(m).open(stale, null)))
    }

    @Test
    fun aForeignGrantPinsItsGranterOnFirstUseAndMatchesWithoutAFetchAfter() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        val o = opener()
        assertIs<GrantOutcome.Opened>(o.open(grant(p.randomBytes(32), owner), null))
        assertEquals(1, server.writes, "the first sight was not pinned")
        val (_, pin) = assertNotNull(pins().load().pins.entries.firstOrNull()).toPair()
        assertEquals(owner.chain().head, pin.head)
        assertEquals(0, pin.index)
        assertIs<GrantOutcome.Opened>(o.open(grant(p.randomBytes(32), owner), null))
        assertEquals(1, server.chainFetches, "a pinned key was looked up again")
        assertEquals(1, server.writes)
    }

    @Test
    fun aBadSignatureIsRefusedBeforeAnyKeyIsLookedUpOrPinned() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        assertEquals(GrantOutcome.Reason.BAD_SIGNATURE, reason(opener().open(grant(p.randomBytes(32), owner, tamper = true), null)))
        assertEquals(0, server.chainFetches)
        assertEquals(0, server.writes)
    }

    @Test
    fun anotherAccountsGrantOfThePersonalScopeIsRefused() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        assertEquals(GrantOutcome.Reason.NOT_SELF_SIGNED, reason(opener().open(grant(p.randomBytes(32), owner, ScopeKind.PERSONAL), null)))
    }

    @Test
    fun aChainThatAdvancedMovesThePinAndOneThatDoesNotPassItIsRefused() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        val o = opener()
        assertIs<GrantOutcome.Opened>(o.open(grant(p.randomBytes(32), owner), null))
        owner.rotate()
        server.chains[owner.userId] = owner.chain()
        assertIs<GrantOutcome.Opened>(o.open(grant(p.randomBytes(32), owner), null))
        assertEquals(1, pins().load().pins.values.single().index, "the pin did not move")

        // The server swaps in another chain for the same person: it never passes the pinned head.
        val impostor = Person(owner.userId)
        server.chains[owner.userId] = impostor.chain()
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(o.open(grant(p.randomBytes(32), impostor), null)))
    }

    @Test
    fun aRetiredKeyOpensOnlyAtOrBelowTheFloor() = runTest {
        val owner = Person().apply { rotate() }.also { server.chains[it.userId] = it.chain() }
        val m = marks()
        val scopeId = UUID.randomUUID().toString()
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_RETIRED, reason(opener(m).open(grant(p.randomBytes(32), owner, scopeId = scopeId, epoch = 1, at = 0), null)))
        assertIs<GrantOutcome.Opened>(opener(m).open(grant(p.randomBytes(32), owner, scopeId = scopeId, epoch = 0, at = 0), null))
    }

    @Test
    fun aGroupGrantIsJudgedByTheGranterStandingInTheTenant() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        val other = Person().also { server.chains[it.userId] = it.chain() }
        val view = view(member(owner, ScopeMember.Role.OWNER), member(other, ScopeMember.Role.MEMBER))
        assertIs<GrantOutcome.Opened>(opener().open(grant(p.randomBytes(32), owner, ScopeKind.GROUP), view))
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(opener().open(grant(p.randomBytes(32), other, ScopeKind.GROUP), view)))
        val fetches = server.chainFetches
        // A join record that does not open (no wrap of its value key) is no record: unanchored, never another's.
        val junk = view(member(other, ScopeMember.Role.MEMBER, joinRecord = "AAAA"))
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(opener().open(grant(p.randomBytes(32), other, ScopeKind.GROUP), junk)))
        val pending = view(member(owner, ScopeMember.Role.OWNER, pendingConsent = true))
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(opener().open(grant(p.randomBytes(32), owner, ScopeKind.GROUP), pending)))
        assertEquals(fetches, server.chainFetches, "an unanchored granter's chain was read")
        // A tenant this device could not read: the pin alone decides, as on the web.
        assertIs<GrantOutcome.Opened>(opener().open(grant(p.randomBytes(32), other, ScopeKind.GROUP), TenantView.unavailable(tenantId, suite)))
    }

    @Test
    fun aGroupGrantFromAMemberWhoJoinedThroughAnOwnersInviteOpens() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        val joiner = Person().also { server.chains[it.userId] = it.chain() }
        val v = view(member(owner, ScopeMember.Role.OWNER), joinedMember(joiner, owner))
        assertIs<Standing.Joined>(v.standing(joiner.userId))
        assertIs<GrantOutcome.Opened>(opener().open(grant(p.randomBytes(32), joiner, ScopeKind.GROUP), v))
        assertEquals(setOf(owner.userId, joiner.userId), pins().load().pins.keys, "the owner and the member were not both pinned")
    }

    @Test
    fun aJoinRecordThatDoesNotHoldAnchorsNobody() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        val other = Person().also { server.chains[it.userId] = it.chain() }
        val joiner = Person().also { server.chains[it.userId] = it.chain() }
        val o = member(owner, ScopeMember.Role.OWNER)
        val cases = mapOf(
            "the invite key's signature is broken" to view(o, joinedMember(joiner, owner, breakInviteSignature = true)),
            // A member the tenant anchors, but no owner: only an owner roots a record.
            "the signer is no owner" to view(o, joinedMember(other, owner), joinedMember(joiner, other)),
            "the signer is not listed" to view(joinedMember(joiner, owner)),
            "the signer is pending" to view(member(owner, ScopeMember.Role.OWNER, pendingConsent = true), joinedMember(joiner, owner)),
            "the member signed their own record" to view(o, joinedMember(joiner, joiner).copy(role = ScopeMember.Role.OWNER)),
        )
        for ((why, v) in cases) {
            assertEquals(Standing.Unanchored, v.standing(joiner.userId), why)
            assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(opener().open(grant(p.randomBytes(32), joiner, ScopeKind.GROUP), v)), why)
        }
    }

    @Test
    fun aRecordRootedByAKeyNotOnTheOwnersChainAnchorsNobody() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        val joiner = Person().also { server.chains[it.userId] = it.chain() }
        // The server lists the owner with a key of its own and roots the record with it; the chain it serves does not hold it.
        val forged = Person(owner.userId)
        val v = view(member(owner, ScopeMember.Role.OWNER).copy(identityPublicKey = b64(forged.bundle())), joinedMember(joiner, forged))
        assertEquals(Standing.Unanchored, v.standing(joiner.userId))
    }

    @Test
    fun ownersWhoRootedEachOtherAnchorNobody() = runTest {
        val a = Person().also { server.chains[it.userId] = it.chain() }
        val b = Person().also { server.chains[it.userId] = it.chain() }
        val v = view(joinedMember(a, b).copy(role = ScopeMember.Role.OWNER), joinedMember(b, a).copy(role = ScopeMember.Role.OWNER))
        assertEquals(Standing.Unanchored, v.standing(a.userId))
        assertEquals(Standing.Unanchored, v.standing(b.userId))
    }

    @Test
    fun aJoinedMembersKeyCountsOnlyOnTheirChainAndFromTheStepTheyJoinedWith() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        // Joined with their second key: a grant their first one signed is from before they were in the tenant.
        val joiner = Person().apply { rotate() }.also { server.chains[it.userId] = it.chain() }
        val v = view(member(owner, ScopeMember.Role.OWNER), joinedMember(joiner, owner))
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(opener().open(grant(p.randomBytes(32), joiner, ScopeKind.GROUP, at = 0), v)))
        assertIs<GrantOutcome.Opened>(opener().open(grant(p.randomBytes(32), joiner, ScopeKind.GROUP), v))
        // The server serves another chain for them, which does not hold the keys their record names (longer than the
        // pinned one, so it is no rollback: the record's keys are what refuses it).
        val impostor = Person(joiner.userId).apply { rotate(); rotate() }.also { server.chains[it.userId] = it.chain() }
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(opener().open(grant(p.randomBytes(32), impostor, ScopeKind.GROUP), view(member(owner, ScopeMember.Role.OWNER), joinedMember(joiner, owner)))))
    }

    @Test
    fun aRecordTheOwnersRetiredKeySignedCountsOnlyWithTheirCurrentKeysEndorsement() = runTest {
        val owner = Person().apply { rotate() }.also { server.chains[it.userId] = it.chain() }
        val joiner = Person().also { server.chains[it.userId] = it.chain() }
        // Rooted by the owner's first key, which their securing then retired.
        val signedBefore = { endorsedBy: Int? -> joinedMember(joiner, owner, signerAt = 0, endorsedBy = endorsedBy) }
        val records = listOf(signedBefore(null), signedBefore(0), signedBefore(1))
        val o = member(owner, ScopeMember.Role.OWNER)
        assertEquals(Standing.Unanchored, view(o, records[0]).standing(joiner.userId), "no endorsement")
        assertEquals(Standing.Unanchored, view(o, records[1]).standing(joiner.userId), "endorsed by the retired key")
        assertIs<Standing.Joined>(view(o, records[2]).standing(joiner.userId), "endorsed by the current key")
    }

    @Test
    fun aPinOffTheJoinedChainIsReplacedUnlessItWasLonger() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        val joiner = Person().also { server.chains[it.userId] = it.chain() }
        val operators = Person(joiner.userId)
        val store = pins()
        store.mutate(store.load()) { it[joiner.userId] = Trust.Pin(b64(trust.fingerprintHash(operators.bundle())), operators.chain().head, 0, 1, false) }
        val v = view(member(owner, ScopeMember.Role.OWNER), joinedMember(joiner, owner))
        assertIs<GrantOutcome.Opened>(opener().open(grant(p.randomBytes(32), joiner, ScopeKind.GROUP), v))
        val replaced = pins().load().pins.getValue(joiner.userId)
        assertTrue(replaced.replaced, "the pin was not marked replaced")
        assertEquals(joiner.chain().head, replaced.head)

        // A pinned head off the chain at a later step than the chain has: a rollback, not a replacement.
        store.mutate(pins().load()) { it[joiner.userId] = Trust.Pin(b64(trust.fingerprintHash(operators.bundle())), operators.chain().head, 3, 1, false) }
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(opener().open(grant(p.randomBytes(32), joiner, ScopeKind.GROUP), view(member(owner, ScopeMember.Role.OWNER), joinedMember(joiner, owner)))))
    }

    @Test
    fun aLaterEpochMustSucceedTheHeldKey() = runTest {
        val m = marks()
        val scopeId = UUID.randomUUID().toString()
        val k1 = p.randomBytes(32)
        val k2 = p.randomBytes(32)
        assertIs<GrantOutcome.Opened>(opener(m).open(grant(k1, me, ScopeKind.PERSONAL, scopeId, epoch = 1), null))
        assertEquals(GrantOutcome.Reason.EPOCH_UNLINKED, reason(opener(m).open(grant(p.randomBytes(32), me, ScopeKind.PERSONAL, scopeId, epoch = 1), null)))
        assertEquals(GrantOutcome.Reason.EPOCH_UNLINKED, reason(opener(m).open(grant(k2, me, ScopeKind.PERSONAL, scopeId, epoch = 2), null)))
        val pk2 = trust.successionKeyPair(k2).first
        val forged = p.ed25519Sign(trust.successionKeyPair(p.randomBytes(32)).second, trust.scopeSuccessionStatement(scopeId, 2, pk2))
        successions[scopeId] = listOf(ScopeSuccession(2, b64(pk2), b64(forged)))
        assertEquals(GrantOutcome.Reason.EPOCH_UNLINKED, reason(opener(m).open(grant(k2, me, ScopeKind.PERSONAL, scopeId, epoch = 2), null)))
        val signed = p.ed25519Sign(trust.successionKeyPair(k1).second, trust.scopeSuccessionStatement(scopeId, 2, pk2))
        successions[scopeId] = listOf(ScopeSuccession(2, b64(pk2), b64(signed)))
        assertIs<GrantOutcome.Opened>(opener(m).open(grant(k2, me, ScopeKind.PERSONAL, scopeId, epoch = 2), null))
        assertEquals(2, m.held(scopeId)?.epoch)
    }

    @Test
    fun aRolledBackOrTamperedPinStoreIsRefusedNeverReadAsEmpty() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        assertIs<GrantOutcome.Opened>(opener().open(grant(p.randomBytes(32), owner), null))
        val (rev, blob, entries) = Triple(server.revision, server.blob, server.entries.toMap())

        // An entry withheld under an unchanged head: the digest no longer matches.
        server.entries.clear()
        assertEquals(TrustStateRefused.Code.ROLLED_BACK, assertFailsWith<TrustStateRefused> { pins().load() }.code)
        assertEquals(GrantOutcome.Reason.TRUST_STATE, reason(opener().open(grant(p.randomBytes(32), owner), null)))

        // The whole store served as never written, after this device opened it.
        server.revision = 0; server.blob = null
        assertEquals(TrustStateRefused.Code.ROLLED_BACK, assertFailsWith<TrustStateRefused> { pins().load() }.code)

        // The same head and entries again: they open. On another device (no marks) a missing store is the empty one.
        server.revision = rev; server.blob = blob; server.entries.putAll(entries)
        assertEquals(1, pins().load().pins.size)
        server.revision = 0; server.blob = null; server.entries.clear()
        assertEquals(0, pins(marks(MemoryDeviceStore())).load().pins.size)
    }

    @Test
    fun anEntryMovedToAnotherContactsSlotIsUnreadable() = runTest {
        val a = Person().also { server.chains[it.userId] = it.chain() }
        val b = Person().also { server.chains[it.userId] = it.chain() }
        opener().open(grant(p.randomBytes(32), a), null)
        opener().open(grant(p.randomBytes(32), b), null)
        val ids = server.entries.keys.toList()
        val swapped = server.entries.toMap()
        server.entries[ids[0]] = swapped.getValue(ids[1])
        server.entries[ids[1]] = swapped.getValue(ids[0])
        // The digest moved too, so re-head the store as a server could: the entries still do not open at their ids.
        val digest = trust.identityPinSetDigest(server.entries.map { (id, e) -> unhex(id) to unb64(e) })
        server.blob = b64(suite.seal(Context("identity-pins", "revision" to server.revision), masterKey, trust.encodePinHead(2, digest)))
        assertEquals(TrustStateRefused.Code.UNREADABLE, assertFailsWith<TrustStateRefused> { pins().load() }.code)
    }

    /** The store's head re-sealed over what the server now holds, as a writer holding the Master Key would. */
    private fun reHead() {
        val digest = trust.identityPinSetDigest(server.entries.map { (id, e) -> unhex(id) to unb64(e) })
        server.revision++
        server.blob = b64(suite.seal(Context("identity-pins", "revision" to server.revision), masterKey, trust.encodePinHead(server.entries.size, digest)))
    }

    @Test
    fun anEntrySealedAtAnotherContactsIdIsUnreadable() = runTest {
        val a = Person()
        val b = Person()
        val indexKey = trust.identityPinIndexKey(masterKey)
        val idOfB = trust.identityPinId(indexKey, b.userId)
        val pinOfA = Trust.Pin(b64(trust.fingerprintHash(a.bundle())), a.chain().head, 0, 1, false)
        server.entries[hex(idOfB)] =
            b64(suite.seal(Context("identity-pin", "pinId" to idOfB), masterKey, trust.encodePinEntry(a.userId, pinOfA)))
        reHead()
        assertEquals(TrustStateRefused.Code.UNREADABLE, assertFailsWith<TrustStateRefused> { pins().load() }.code)
    }

    @Test
    fun aPinWhoseKeyIsNotTheKeyAtItsHeadIsAnAnomaly() = runTest {
        val owner = Person().also { server.chains[it.userId] = it.chain() }
        val store = pins()
        store.mutate(store.load()) { it[owner.userId] = Trust.Pin(b64(trust.fingerprintHash(Person().bundle())), owner.chain().head, 0, 1, false) }
        assertEquals(GrantOutcome.Reason.GRANTER_KEY_UNANCHORED, reason(opener().open(grant(p.randomBytes(32), owner), null)))
    }

    @Test
    fun aWriteThatMeetsAnotherDevicesWriteReappliesOnTopOfIt() = runTest {
        val a = Person().also { server.chains[it.userId] = it.chain() }
        val b = Person().also { server.chains[it.userId] = it.chain() }
        val otherDevice = opener(marks(MemoryDeviceStore()))
        server.beforeWrite = { assertIs<GrantOutcome.Opened>(otherDevice.open(grant(p.randomBytes(32), b), null)) }
        assertIs<GrantOutcome.Opened>(opener().open(grant(p.randomBytes(32), a), null))
        assertEquals(setOf(a.userId, b.userId), pins().load().pins.keys)
    }
}
