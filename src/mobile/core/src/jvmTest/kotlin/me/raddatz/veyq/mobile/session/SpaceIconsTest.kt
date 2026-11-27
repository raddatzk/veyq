package me.raddatz.veyq.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unhex
import me.raddatz.veyq.mobile.session.rotation.ScopeRotation
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A space's icon as the web seals it (`spaceIcon.ts` `sealIcon` under `valueKey.ts` `sealValue`, `scopes.ts` `setSpaceIcon`):
 * a fixture the web's `@veyq/crypto` wrote (`space-icon-web.json`: 25 bytes padded to 16 KiB under a fresh value key, the
 * key's wrap under a scope key of 32 × 0x07 at epoch 3, icon epoch 2) opens here, and what [SpaceIcons.seal] writes opens
 * back at the one constant length. **Load-bearing:** an icon opens only at its own scope, icon epoch and key epoch — bitten by
 * sealing under `iconEpoch + 1` in [SpaceIcons.context] (red: the fixture opens nothing). Beside it the two pure rules of the
 * Members panel's rest: the trust badge's precedence and the rotation's member list.
 */
class SpaceIconsTest {
    private val suite = V1(BouncyCastlePrimitives)
    private val scopeId = "c0ffee00-0000-4000-8000-0000000000a1"
    private val key = unhex("0707070707070707070707070707070707070707070707070707070707070707")
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/space-icon-web.json")!!.readText()).jsonObject
    private val sealed = fixture.getValue("sealed").jsonPrimitive.content
    private val wrapped = fixture.getValue("wrapped").jsonPrimitive.content

    @Test
    fun theWebsIconOpensAtItsOwnAddressOnly() {
        assertEquals("RIFF-not-really-webp-icon", SpaceIcons.open(suite, scopeId, 3, key, 2, sealed, wrapped)?.decodeToString())
        assertNull(SpaceIcons.open(suite, scopeId, 3, key, 1, sealed, wrapped), "another icon epoch")
        assertNull(SpaceIcons.open(suite, scopeId, 4, key, 2, sealed, wrapped), "another key epoch")
        assertNull(SpaceIcons.open(suite, "c0ffee00-0000-4000-8000-0000000000a2", 3, key, 2, sealed, wrapped), "another scope")
        assertNull(SpaceIcons.open(suite, scopeId, 3, ByteArray(32) { 8 }, 2, sealed, wrapped), "another key")
        assertNull(SpaceIcons.open(suite, scopeId, 3, key, 2, sealed, null), "no wrap")
    }

    @Test
    fun aSealedIconOpensBackAndEverySealHasTheOneLength() {
        val small = SpaceIcons.seal(suite, scopeId, 5, key, byteArrayOf(1, 2, 3))
        val large = SpaceIcons.seal(suite, scopeId, 5, key, ByteArray(16_000) { it.toByte() })
        assertContentEquals(byteArrayOf(1, 2, 3), SpaceIcons.open(suite, scopeId, 5, key, 5, small.sealedIcon, small.wrappedIconValueKey))
        assertEquals(sealed.length, small.sealedIcon.length, "the web's length: a logo and a photo look alike")
        assertEquals(small.sealedIcon.length, large.sealedIcon.length)
        assertEquals(21_884, small.sealedIcon.length, "the contract's @Size(max = 21884)")
    }

    private fun other(id: String, state: WhosWho.State, isNew: Boolean) =
        WhosWho.Other(id, id, id, state, isNew, emptyList(), false, null, false)

    /** **Load-bearing:** *Verified* wins over *New*, *Not verified* and an unchecked member show nothing (the web's `badgesIn`);
     *  bitten by testing `isNew` before the state (red: a verified newcomer reads *New*). */
    @Test
    fun theBadgeIsTheWebs() {
        val badges = MemberBadge.of(
            WhosWho(
                listOf(
                    other("a", WhosWho.State.VERIFIED, isNew = true), other("b", WhosWho.State.NOT_VERIFIED, isNew = true),
                    other("c", WhosWho.State.NOT_VERIFIED, isNew = false), other("d", WhosWho.State.CANT_CONFIRM, isNew = false),
                    other("e", WhosWho.State.UNCHECKED, isNew = true),
                ),
                null, false,
            ),
        )
        assertEquals(mapOf("a" to MemberBadge.VERIFIED, "b" to MemberBadge.NEW, "d" to MemberBadge.ANOMALY), badges)
        assertEquals(Msg.TRUST_ANOMALY_NOTE, MemberBadge.ANOMALY.note)
        val row = TenantMembers.assemble(listOf(member("d")), owner = true, badges) { null }.rows.single()
        assertEquals(Msg.TRUST_ANOMALY, row.badgeWord)
    }

    private fun member(id: String) = ScopeMember(id, id, 0, ScopeMember.Role.MEMBER, "AAAA", "AAAA", false)

    /** **Load-bearing:** a new lock after a removal grants to nobody the owner excluded, however often the server lists them;
     *  bitten by dropping `excluding` from [ScopeRotation.listedFor] (red: "x" is granted). */
    @Test
    fun aNewLockGrantsNoExcludedOrRemovedMember() {
        val listed = listOf(member("a"), member("x"), member("r"), member("x"))
        assertEquals(listOf("a"), ScopeRotation.listedFor(listed, removes = "r", excluding = "x").map { it.userId })
        assertEquals(listOf("a", "x", "x"), ScopeRotation.listedFor(listed, removes = "r", excluding = null).map { it.userId })
    }

    @Test
    fun theLinksTurnedOffAreCounted() {
        assertEquals(Notice(Msg.SPACE_LINKS_REVOKED_NONE), Locks.revokedNotice(0))
        assertEquals(Notice(Msg.SPACE_LINKS_REVOKED, 3), Locks.revokedNotice(3))
    }
}
