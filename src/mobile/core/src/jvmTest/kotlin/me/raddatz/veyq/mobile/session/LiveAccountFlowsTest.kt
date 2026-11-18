package me.raddatz.veyq.mobile.session

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.media.WebpEncoder
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The account's flows the core ports (ADR 0148) against a running dev instance, as a throwaway tenant's two accounts
 * ([Throwaway]: `VEYQ_LIVE` and `VEYQ_LIVE_THROWAWAY_ENV`, a file `throwaway:account -- --with-member` wrote) — never the
 * seeded principal or member. Every device a case signs in with is taken off its account again ([LiveRun]); the accounts
 * themselves go with the throwaway's `--erase`.
 */
class LiveAccountFlowsTest {
    private val p = BouncyCastlePrimitives
    private val client = { block: io.ktor.client.HttpClientConfig<*>.() -> Unit -> HttpClient(OkHttp, block) }
    private val runs = mutableListOf<LiveRun>()

    @AfterTest
    fun takeOffTheDevices() = runs.forEach { it.removeAll() }

    private suspend fun session(origin: String, t: Throwaway.Accounts, who: String): TenantSession {
        val run = LiveRun(who, t.file).also { runs += it }
        val account = VeyqAccount.signIn(
            p, origin, t.env.getValue("${who}_EMAIL"), t.env.getValue("${who}_PASSWORD"), t.env.getValue("${who}_SECRET_KEY"), run.device(), client,
        )
        return account.open(account.tenants.first { it.domain == t.env.getValue("VEYQ_TENANT_DOMAIN") })
    }

    /** uc.spaces.enforce-verified-identities (ADR 0148 decision 3): the two members verify each other, by the digits one
     *  way and by a scanned code the other, and each reads the other's verification. */
    @Test
    fun twoMembersVerifyEachOtherAndEachReadsTheOthersRow() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        runTest(timeout = 4.minutes) {
            val owner = session(origin, t, "VEYQ")
            val member = session(origin, t, "VEYQ_USER2")
            val ownerId = owner.account.userId
            val memberId = member.account.userId

            val mine = assertNotNull(member.personTrust.mine(), "the member's own chain does not end at its identity")
            val seen = assertNotNull(owner.personTrust.person(memberId))
            assertFalse(seen.anomaly, "the member is not anchored in their own tenant")
            assertEquals(mine.number, seen.number, "the owner's and the member's digits differ")
            assertEquals(mine.chainCode, seen.chainCode)
            // the owner compares the digits read aloud
            assertEquals(PersonTrust.Outcome.VERIFIED, owner.personTrust.compareDigits(memberId, true))
            // the member scans the owner's code
            val ownersCode = assertNotNull(owner.personTrust.mine()).payload
            assertNotNull(member.personTrust.person(ownerId))
            assertEquals(PersonTrust.Outcome.FOREIGN, member.personTrust.compareScan(ownerId, "veyqkit:1:nothing"))
            assertEquals(PersonTrust.Outcome.VERIFIED, member.personTrust.compareScan(ownerId, ownersCode))

            // each reads its own row and the other's: the owner's of the member counts for the member's subject, and the
            // member's of the owner for the owner's — read by the other side
            val ownerReads = assertNotNull(owner.personTrust.person(memberId))
            assertTrue(ownerReads.verifiedByMe)
            assertEquals(PersonTrust.Status.VERIFIED, ownerReads.status)
            val memberReads = assertNotNull(member.personTrust.person(ownerId))
            assertTrue(memberReads.verifiedByMe)
            assertEquals(listOf(ownerId), member.personTrust.countedOf(memberId), "the member does not read the owner's verification of them")
            assertEquals(listOf(memberId), owner.personTrust.countedOf(ownerId), "the owner does not read the member's verification of them")
        }
    }

    /** uc.spaces.edit-profile (ADR 0148 decision 2): the owner's name set through the core, read by the member's directory. */
    @Test
    fun aNameSetIsReadByTheOtherMember() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        runTest(timeout = 3.minutes) {
            val owner = session(origin, t, "VEYQ")
            val member = session(origin, t, "VEYQ_USER2")
            val model = owner.profile()
            try {
                model.load()
                assertTrue(model.current.hasKey)
                val last = "Live ${System.currentTimeMillis()}"
                model.setFirstName("App")
                model.setLastName(last)
                model.save()
                assertNull(model.current.refusal)
                assertEquals(1, model.current.saves)
                assertEquals("App $last", owner.people[owner.account.userId]?.displayName, "the owner's own people were not read again")
                val people = assertNotNull(member.readPeople())
                assertEquals("App $last", people[owner.account.userId]?.displayName)
            } finally {
                model.close()
            }
        }
    }

    /**
     * uc.spaces.set-profile-picture (ADR 0148 decision 2): a picture set through the core — encoded by the encoder the
     * seam is handed (here a fixed real WebP: the JVM has no libwebp; each encoder is the native tests'), padded and
     * sealed —, read back by the member's `MemberAvatars` byte for byte, then cleared and gone for the member too.
     */
    @Test
    fun aPictureSetIsReadBackByTheOtherMemberAndCleared() {
        val origin = Throwaway.origin()
        val t = Throwaway.accounts()
        assertTrue(t.hasMember, "the throwaway has no member: create it with --with-member")
        val webp = File(System.getProperty("veyq.repoRoot"), "src/web/src/features/memories/__fixtures__/exif-sample.webp").readBytes()
        runTest(timeout = 3.minutes) {
            val owner = session(origin, t, "VEYQ")
            val member = session(origin, t, "VEYQ_USER2")
            val ownerId = owner.account.userId
            val model = owner.profile(WebpEncoder { _, _, _, _ -> webp })
            try {
                model.load()
                model.choosePicture(ByteArray(ProfileModel.EDGE * ProfileModel.EDGE * 4))
                model.save()
                assertNull(model.current.pictureRefusal)
                assertTrue(model.current.serverHasPicture)
                assertContentEquals(webp, owner.avatars.pictureOf(ownerId), "the owner's own pictures did not follow")
                member.avatars.sync(assertNotNull(member.readPeople()).values)
                assertContentEquals(webp, member.avatars.pictureOf(ownerId))

                model.removePicture()
                model.save()
                assertFalse(model.current.serverHasPicture)
                member.avatars.sync(assertNotNull(member.readPeople()).values)
                assertNull(member.avatars.pictureOf(ownerId), "the cleared picture still shows")
            } finally {
                model.close()
            }
        }
    }

    /** ADR 0148 decision 12's guard: a throwaway file naming the principal FAILS a case, never skips it. */
    @Test
    fun aThrowawayFileNamingTheSeededPrincipalFails() {
        val seeded = System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")
        val dir = kotlin.io.path.createTempDirectory("throwaway-guard").toFile()
        try {
            val fakeSeeded = File(dir, "seeded.env").apply { writeText("VEYQ_EMAIL=principal@a.example\nVEYQ_USER2_EMAIL=member@a.example\n") }
            val naming = File(dir, "naming.env").apply { writeText("VEYQ_EMAIL= Principal@A.example\n") }
            val other = File(dir, "other.env").apply { writeText("VEYQ_EMAIL=tw-1@veyq.localhost\nVEYQ_USER2_EMAIL=member@a.example\n") }
            val fine = File(dir, "fine.env").apply { writeText("VEYQ_EMAIL=tw-1@veyq.localhost\n") }
            assertTrue(runCatching { Throwaway.accountsOf(naming, fakeSeeded) }.exceptionOrNull() is AssertionError, "the principal's file was taken")
            assertTrue(runCatching { Throwaway.accountsOf(other, fakeSeeded) }.exceptionOrNull() is AssertionError, "the member's address was taken")
            assertEquals("tw-1@veyq.localhost", Throwaway.accountsOf(fine, fakeSeeded).email())
            // the real seeded file, where this checkout has one: naming it as the throwaway fails
            if (seeded.isFile) assertTrue(runCatching { Throwaway.accountsOf(seeded, seeded) }.exceptionOrNull() is AssertionError)
        } finally {
            dir.deleteRecursively()
        }
    }
}
