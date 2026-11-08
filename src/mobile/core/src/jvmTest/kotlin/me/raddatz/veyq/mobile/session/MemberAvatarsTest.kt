package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.Pad
import me.raddatz.veyq.mobile.crypto.Purposes
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * A member's picture opened as the web's `openMemberAvatar` opens it: its value key from the wrap under the tenant's key,
 * the picture under it bound to (tenant scope, member), the one constant pad, an image by its bytes — anything else the
 * letter (null), never an error.
 */
class MemberAvatarsTest {
    private val suite = V1(BouncyCastlePrimitives)
    private val joins = JoinRecords(suite)
    private val tenant = "7e000000-0000-4000-8000-000000000001"
    private val anna = "6e000000-0000-4000-8000-000000000001"
    private val ben = "6e000000-0000-4000-8000-000000000002"
    private val tenantKey = ByteArray(32) { 7 }
    private val webp = "RIFF".encodeToByteArray() + byteArrayOf(0x20, 0, 0, 0) + "WEBPVP8 ".encodeToByteArray() + ByteArray(40) { 1 }

    /** A picture sealed as the web's `sealAvatar` seals it, for [userId] at [epoch], padded to [padTo]. */
    private fun sealed(bytes: ByteArray, userId: String = anna, epoch: Long = 1, padTo: Int = MemberAvatars.PADDED_LEN): Pair<String, String> {
        val ctx = MemberAvatars.context(tenant, userId)
        val valueKey = ByteArray(32) { (it + 1).toByte() }
        val wrapped = suite.seal(joins.valueKeyContext(tenant, epoch, joins.valueAddress(Purposes.FIELD, ctx)), tenantKey, valueKey)
        return b64(suite.sealField(ctx, valueKey, Pad.padTo(bytes, padTo))) to b64(wrapped)
    }

    private fun open(s: Pair<String, String>?, userId: String = anna, epoch: Long = 1, key: ByteArray = tenantKey) =
        MemberAvatars.open(joins, tenant, key, epoch, userId, s?.first, s?.second)

    @Test
    fun aPictureOpensToItsImage() {
        assertContentEquals(webp, open(sealed(webp)))
    }

    @Test
    fun anythingElseIsTheLetter() {
        // None stored.
        assertNull(open(null))
        // Another member's picture under this one's address: its value key's address and its seal are another's.
        assertNull(open(sealed(webp, userId = ben)))
        // A wrap of another epoch, or under another key.
        assertNull(open(sealed(webp), epoch = 2))
        assertNull(open(sealed(webp), key = ByteArray(32) { 9 }))
        // The first pictures' 8 KiB pad is no picture of ours any more.
        assertNull(open(sealed(webp, padTo = 8_192)))
        // Bytes that are no image.
        assertNull(open(sealed("<svg onload=x>".encodeToByteArray())))
        // A seal taken apart.
        val s = sealed(webp)
        assertNull(open(s.first.dropLast(8) + "AAAAAAA=" to s.second))
    }
}
