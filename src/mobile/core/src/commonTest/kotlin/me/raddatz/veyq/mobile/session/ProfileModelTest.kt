package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.api.models.MemberProfile
import me.raddatz.veyq.mobile.api.models.ProfileUpdateRequest
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.SetAvatarRequest
import me.raddatz.veyq.mobile.crypto.JoinRecords
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.media.WebpEncoder
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One's own profile (uc.spaces.edit-profile, uc.spaces.set-profile-picture; ADR 0148 decision 2) over a server the test
 * plays, with real seals: what the model writes is opened by every reader's own opener (`People.realName`,
 * `MemberAvatars.open`), never by the model.
 */
class ProfileModelTest {
    private val p = testPrimitives
    private val suite = V1(p)

    /** A tenant whose key moved to [currentEpoch]; the model is handed the key of [heldEpoch] until it asks anew. */
    private inner class Played(var heldEpoch: Long = 3, val currentEpoch: Long = 3, val hasGrant: Boolean = true) : ProfileModel.Door {
        override val tenantId = "c0ffee00-0000-4000-8000-0000000000f1"
        override val userId = "c0ffee00-0000-4000-8000-00000000000a"
        val keys = mapOf(2L to p.randomBytes(32), 3L to p.randomBytes(32))
        var username = "kevin"
        var sealedName: String? = null
        var wrappedName: String? = null
        var avatar: SetAvatarRequest? = null
        var avatarRevision = 0
        var consent = false
        val taken = setOf("taken")
        val updates = mutableListOf<ProfileUpdateRequest>()
        val avatarWrites = mutableListOf<SetAvatarRequest>()
        val handedOut = mutableListOf<ByteArray>()
        var peopleRead = 0
        var avatarStatus = 204

        /** The name as it was written at [at] (the key the model opened with, before a rotation moved it on). */
        fun seedName(first: String, last: String, at: Long = heldEpoch) {
            val s = JoinRecords(suite).sealValueField(tenantId, at, keys.getValue(at), People.nameContext(tenantId, userId),
                utf8("""{"firstName":"$first","lastName":"$last"}"""))
            sealedName = b64(s.sealed)
            wrappedName = b64(s.wrappedValueKey)
        }

        /** The name as every member reads it from the directory, with the tenant's current key. */
        fun nameAsMembersReadIt(): String? = People.realName(
            JoinRecords(suite), tenantId, keys.getValue(currentEpoch), currentEpoch,
            ScopeMember(userId, username, avatarRevision, ScopeMember.Role.MEMBER, "", "", false, sealedName, wrappedName),
        )

        override suspend fun profile() = MemberProfile(username, avatarRevision, consent, false, sealedName, wrappedName)
        override suspend fun tenantKey(fresh: Boolean): Pair<ByteArray, Long>? {
            if (!hasGrant) return null
            if (fresh) heldEpoch = currentEpoch
            return (keys.getValue(heldEpoch).copyOf() to heldEpoch).also { handedOut += it.first }
        }
        override suspend fun update(body: ProfileUpdateRequest): ProfileModel.Reply {
            updates += body
            if (body.epoch != null && body.epoch!!.toLong() != currentEpoch) return ProfileModel.Reply(409, "stale_epoch")
            if (body.username in taken) return ProfileModel.Reply(409, "username_taken")
            body.username?.let { username = it }
            if (body.sealedName != null) { sealedName = body.sealedName; wrappedName = body.wrappedNameValueKey }
            body.requireGroupAddConsent?.let { consent = it }
            return ProfileModel.Reply(200)
        }
        override suspend fun setAvatar(body: SetAvatarRequest): ProfileModel.Reply {
            avatarWrites += body
            if (body.epoch!!.toLong() != currentEpoch) return ProfileModel.Reply(409, "stale_epoch")
            if (avatarStatus !in 200..299) return ProfileModel.Reply(avatarStatus)
            avatar = body
            avatarRevision++
            return ProfileModel.Reply(204)
        }
        override suspend fun clearAvatar(): ProfileModel.Reply { avatar = null; avatarRevision++; return ProfileModel.Reply(204) }
        /** The session's people half, when a test wires one ([OwnPeople] over an [AvatarsModel]); else only counted. */
        var own: OwnPeople? = null
        /** What a host would see mid-save, as the people are read anew (the bridge's `Profile.PictureBytes`). */
        var duringPeople: (() -> Unit)? = null
        override suspend fun peopleChanged() { peopleRead++; duringPeople?.invoke(); own?.peopleChanged() }
        override fun picture(): ByteArray? = own?.picture()

        /** The members as the directory lists them now: one's own row with its picture revision. */
        fun people(): List<Person> = listOf(Person(userId, username, username, avatarRevision))

        /** The session's pictures over this server: a sync opens one's own picture as every member does. */
        fun pictures(): AvatarsModel = AvatarsModel(
            object : AvatarsModel.Avatars {
                val held = HashMap<String, ByteArray>()
                override suspend fun sync(people: Collection<Person>): Boolean {
                    held.clear()
                    pictureAsMembersOpenIt()?.let { held[userId] = it }
                    return true
                }
                override fun pictures(): Map<String, ByteArray> = held.toMap()
            },
        ) { people() }

        /** The picture as every member opens it (`MemberAvatars.open`). */
        fun pictureAsMembersOpenIt(): ByteArray? = avatar?.let {
            MemberAvatars.open(JoinRecords(suite), tenantId, keys.getValue(currentEpoch), currentEpoch, userId, it.sealedAvatar, it.wrappedAvatarValueKey)
        }
    }

    @Test
    fun theNameOpensAndASaveSealsItForEveryReader() = runTest {
        val s = Played().apply { seedName("Kevin", "R") }
        val m = ProfileModel(s, suite, null)
        m.load()
        assertEquals("Kevin", m.current.firstName)
        assertEquals("R", m.current.lastName)
        assertTrue(m.current.hasKey)
        m.setFirstName("  Kev ")
        m.setUsername(" Kevin.R ")
        m.setRequireGroupAddConsent(true)
        m.save()
        assertEquals("Kev R", s.nameAsMembersReadIt())
        assertEquals("kevin.r", s.username)
        assertTrue(s.consent)
        assertEquals(1, m.current.saves)
        assertEquals(1, s.peopleRead, "the people were not read again")
        assertNull(m.current.refusal)
    }

    @Test
    fun anEmptyNameIsHowANameIsRemoved() = runTest {
        val s = Played().apply { seedName("Kevin", "R") }
        val m = ProfileModel(s, suite, null)
        m.load()
        m.setFirstName("")
        m.setLastName(" ")
        m.save()
        assertNull(s.nameAsMembersReadIt(), "an empty name still reads as a name")
        assertTrue(s.updates.single().sealedName != null, "the empty name was not sent")
    }

    // ADR 0115 decision 3: a rotation replaced the tenant key since the model opened.
    @Test
    fun aStaleEpochOpensTheTenantKeyAnewAndSealsOnceMore() = runTest {
        val s = Played(heldEpoch = 2, currentEpoch = 3).apply { seedName("Kevin", "R") }
        val m = ProfileModel(s, suite, null)
        m.load()
        m.setLastName("Raddatz")
        m.save()
        assertEquals(listOf(2, 3), s.updates.map { it.epoch })
        assertEquals("Kevin Raddatz", s.nameAsMembersReadIt())
        assertNull(m.current.refusal)
        assertEquals(1, m.current.saves)
    }

    @Test
    fun aTakenUsernameIsTheFieldsRefusalAndNoPictureIsSent() = runTest {
        val s = Played()
        val m = ProfileModel(s, suite, FixedEncoder(webp(1000)))
        m.load()
        m.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
        m.setUsername("taken")
        m.save()
        assertEquals(Msg.PROFILE_USERNAME_TAKEN, m.current.usernameRefusal)
        assertNull(m.current.refusal)
        assertTrue(s.avatarWrites.isEmpty())
        assertEquals(0, m.current.saves)
    }

    @Test
    fun withoutATenantGrantOnlyTheUsernameSaves() = runTest {
        val s = Played(hasGrant = false)
        val m = ProfileModel(s, suite, null)
        m.load()
        assertFalse(m.current.hasKey)
        m.setFirstName("Kevin")
        assertEquals("", m.current.firstName, "a name was typed without a key")
        m.setUsername("other")
        m.save()
        val sent = s.updates.single()
        assertEquals("other", sent.username)
        assertNull(sent.sealedName)
        assertNull(sent.epoch)
    }

    @Test
    fun closingZeroesTheTenantKeyAndAnswersNothingAfter() = runTest {
        val s = Played()
        val m = ProfileModel(s, suite, null)
        m.load()
        m.close()
        assertTrue(s.handedOut.isNotEmpty())
        assertTrue(s.handedOut.all { k -> k.all { it == 0.toByte() } }, "a tenant key copy outlived the model")
        m.setUsername("late")
        m.save()
        assertTrue(s.updates.isEmpty())
    }

    // ---- the picture (ADR 0148 decision 2: the core encodes, steps the quality down, pads and seals) ----

    @Test
    fun aPictureIsSteppedDownToTheBudgetPaddedSealedAndOpensForEveryReader() = runTest {
        val big = webp(ProfileModel.BUDGET + 1)
        val fits = webp(ProfileModel.BUDGET)
        val enc = LadderEncoder(mapOf(0.9 to big, 0.82 to fits))
        val s = Played()
        val m = ProfileModel(s, suite, enc)
        m.load()
        m.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
        assertEquals(listOf(0.9, 0.82), enc.asked)
        assertEquals(ProfileModel.PictureDraft.SET, m.current.picture)
        assertContentEquals(fits, m.picture())
        m.save()
        val sent = s.avatarWrites.single()
        // the contract's one sealed length: 1 + 16 + 12 + 16384 + 16 bytes, base64 (`AVATAR_SEALED_B64_LEN`)
        assertEquals(21908, sent.sealedAvatar.length)
        assertContentEquals(fits, s.pictureAsMembersOpenIt())
        assertEquals(ProfileModel.PictureDraft.KEEP, m.current.picture)
        assertTrue(m.current.serverHasPicture)
    }

    // The contract's `maxLength` of `sealedAvatar` is the one length every picture seals to, whatever its encoded size: the
    // pad hides a photo from a drawing (ADR 0084 decision 3).
    @Test
    fun everyPictureSealsToTheContractsOneLength() = runTest {
        for (size in listOf(200, 9000, ProfileModel.BUDGET)) {
            val s = Played()
            val m = ProfileModel(s, suite, FixedEncoder(webp(size)))
            m.load()
            m.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
            m.save()
            assertEquals(21908, s.avatarWrites.singleOrNull()?.sealedAvatar?.length, "a $size-byte picture")
        }
    }

    @Test
    fun aPictureNoQualityFitsIsRefusedAndTheDraftStays() = runTest {
        val s = Played()
        val m = ProfileModel(s, suite, FixedEncoder(webp(ProfileModel.BUDGET + 1)))
        m.load()
        m.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
        assertEquals(Msg.PROFILE_PICTURE_FAILED, m.current.pictureRefusal)
        assertEquals(ProfileModel.PictureDraft.KEEP, m.current.picture)
    }

    @Test
    fun aPictureThatDoesNotSaveLeavesTheProfileSavedAndSaysSo() = runTest {
        val s = Played().apply { avatarStatus = 500 }
        val m = ProfileModel(s, suite, FixedEncoder(webp(800)))
        m.load()
        m.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
        m.setUsername("kevin2")
        m.save()
        assertEquals("kevin2", s.username)
        assertEquals(Msg.PROFILE_PICTURE_SAVE_FAILED, m.current.pictureRefusal)
        assertEquals(ProfileModel.PictureDraft.SET, m.current.picture, "the draft went before it was saved")
    }

    /**
     * Inbox #980: a saved picture shows at once wherever the session's pictures are drawn (the person page, the switcher,
     * the bridge's `ShellShown.picturesRound`) — the door syncs the people through the session's [AvatarsModel], never
     * [MemberAvatars] alone, which told no watcher. Bitten by an [OwnPeople.peopleChanged] that skips
     * [AvatarsModel.peopleRead]: red.
     */
    @Test
    fun aSavedPictureReachesTheSessionsPicturesAtOnce() = runTest {
        val fits = webp(700)
        val s = Played()
        val pictures = s.pictures()
        pictures.load()
        s.own = OwnPeople({ s.people() }, pictures, s.userId)
        val round = pictures.held.value.round
        val m = ProfileModel(s, suite, FixedEncoder(fits))
        m.load()
        m.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
        m.save()
        assertContentEquals(fits, pictures.state.value[s.userId]?.copyOfRange(0, fits.size), "the session's pictures did not follow the save")
        assertTrue(pictures.held.value.round > round, "the round did not move: a host keeping pictures by id never asks again")
        assertContentEquals(fits, m.picture()?.copyOfRange(0, fits.size))
    }

    /**
     * Inbox #980: the picture asked while the people are read anew — the draft gone, the server's not yet held — is the
     * one just saved, never the letter; and it stays so where the read fails (offline). Bitten by a model that drops the
     * saved bytes with the draft: red.
     */
    @Test
    fun theSavedPictureShowsWhileThePeopleAreReadAnew() = runTest {
        val fits = webp(700)
        val s = Played()
        val m = ProfileModel(s, suite, FixedEncoder(fits))
        m.load()
        m.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
        var asked: ByteArray? = null
        s.duringPeople = { asked = m.picture() }
        m.save()
        assertEquals(1, s.peopleRead)
        assertContentEquals(fits, asked, "mid-save the picture was the letter (NO_PICTURE)")
        assertContentEquals(fits, m.picture(), "the people did not read: the saved picture must still show")
        // and a removal saved draws the letter at once
        m.removePicture()
        m.save()
        assertNull(m.picture())
    }

    @Test
    fun aRemovedPictureIsClearedOnSave() = runTest {
        val s = Played().apply { avatarRevision = 2; avatar = SetAvatarRequest(3, "x", "y") }
        val m = ProfileModel(s, suite, null)
        m.load()
        assertTrue(m.current.hasPicture)
        m.removePicture()
        assertFalse(m.current.hasPicture)
        m.save()
        assertNull(s.avatar)
        assertFalse(m.current.serverHasPicture)
    }

    @Test
    fun aStaleEpochReSealsThePictureToo() = runTest {
        val s = Played(heldEpoch = 2, currentEpoch = 3)
        val m = ProfileModel(s, suite, FixedEncoder(webp(900)))
        m.load()
        m.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
        m.save()
        // the profile's update already re-opened the key; the picture is sealed under it at once
        assertEquals(listOf(3), s.avatarWrites.map { it.epoch })
        assertTrue(s.pictureAsMembersOpenIt() != null)
    }

    /** Bytes the readers take for a WebP: `RIFF` · size · `WEBP` and a body, [n] bytes in all. */
    private fun webp(n: Int): ByteArray = ByteArray(n).also { b ->
        "RIFF".encodeToByteArray().copyInto(b, 0)
        "WEBP".encodeToByteArray().copyInto(b, 8)
        for (i in 12 until n) b[i] = (i * 31).toByte()
    }

    private class FixedEncoder(private val bytes: ByteArray) : WebpEncoder {
        override suspend fun encode(rgba: ByteArray, width: Int, height: Int, quality: Double) = bytes
    }

    private class LadderEncoder(private val byQuality: Map<Double, ByteArray>) : WebpEncoder {
        val asked = mutableListOf<Double>()
        override suspend fun encode(rgba: ByteArray, width: Int, height: Int, quality: Double): ByteArray? {
            asked += quality
            return byQuality[quality]
        }
    }
}
