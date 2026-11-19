package me.raddatz.veyq.mobile.bridge

import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.api.models.AccountErasurePreview
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.ChangePasswordRequest
import me.raddatz.veyq.mobile.api.models.EmailChangeRequest
import me.raddatz.veyq.mobile.api.models.EraseOwnAccountRequest
import me.raddatz.veyq.mobile.api.models.ErasureTenant
import me.raddatz.veyq.mobile.api.models.LoginChallenge
import me.raddatz.veyq.mobile.api.models.MasterKeyWrap
import me.raddatz.veyq.mobile.api.models.MemberProfile
import me.raddatz.veyq.mobile.api.models.ProfileUpdateRequest
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.api.models.SetAvatarRequest
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp
import me.raddatz.veyq.mobile.navigation.Connection
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.RecentUses
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.AccountErasure
import me.raddatz.veyq.mobile.session.AccountSettings
import me.raddatz.veyq.mobile.session.AddressChange
import me.raddatz.veyq.mobile.session.ColdCredential
import me.raddatz.veyq.mobile.session.CredentialDoor
import me.raddatz.veyq.mobile.session.FlowReply
import me.raddatz.veyq.mobile.session.PasswordChange
import me.raddatz.veyq.mobile.session.ProfileModel
import me.raddatz.veyq.mobile.session.SessionModel
import me.raddatz.veyq.mobile.session.SessionModel.Gate
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.session.rotation.KeyRotation
import me.raddatz.veyq.mobile.words.Msg
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The account's flows through the bridge (ADR 0148 decision 11): each `*.Open` answered with an id, its watch delivering
 * the core model's state, each call one line onto the model, the typed secrets inbound only, the kit written by the core
 * at the path the host's dialog gave (never bytes) — and no flow outliving its tenant: a sign-out drops every one, each
 * watch delivers null and each call is refused. The account is played: a session door of the test's own, the core's real
 * models over a credential door and a rotation engine the test plays.
 */
class AccountFlowsDispatchTest {
    private val p = BouncyCastlePrimitives
    private val suite = V1(p)

    private data class Acc(override val key: String, override val tenants: List<TenantRef>) : Connection {
        override val label: String get() = key.substringBefore('#')
    }

    private class Door : SessionModel.Door<Acc, String> {
        val account = Acc("a.example#u1", listOf(TenantRef("ta", "A", "a.example")))
        /** This device's trust in [account]: none, unless a test sets one (a password change takes it, [Played.trustTaken]). */
        @Volatile var record: TrustedDevice? = null
        override val primitives = BouncyCastlePrimitives
        override fun trusted() = emptyList<TrustedDevice>()
        override suspend fun signIn(server: String, email: String, password: String, secretKey: String) = account
        override suspend fun signInWithKey(server: String, email: String, password: String, secretKey: ByteArray): Acc = error("no kit")
        override suspend fun unlockOnDevice(record: TrustedDevice, password: String) = error("no trusted device")
        override suspend fun resume(record: TrustedDevice) = error("no trusted device")
        override suspend fun trust(account: Acc, password: String) {}
        override suspend fun stay(account: Acc, level: StaySignedIn) = TrustChange.FAILED
        override suspend fun trustWithPassword(account: Acc, password: String) = TrustChange.FAILED
        override fun defaultTenant(account: Acc) = account.tenants.firstOrNull()
        override fun trustedBy(account: Acc): TrustedDevice? = record
        override suspend fun open(account: Acc, tenant: TenantRef) = "${account.key}/${tenant.tenantId}"
        override suspend fun spaces(session: String) = listOf(SpaceRef(session, "SHARED", session))
        override suspend fun signOut(account: Acc) {}
        override suspend fun forgetDevice(account: Acc) {}
        override fun staySignedIn() = levels
        var levels = emptySet<StaySignedIn>()
        override fun declined(account: Acc) = false
        override fun setDeclined(account: Acc, declined: Boolean) {}
    }

    /** The account's credentials as the test plays them: a real floor under [password] and [secretKey], the requests sent. */
    private inner class Played : CredentialDoor {
        override val userId = "0e000000-0000-4000-a000-000000000001"
        override val email = "p@a.example"
        override val server = "http://a.example"
        val password = "correct horse battery"
        val secretKey: ByteArray = p.randomBytes(32)
        private val mk = p.randomBytes(32)
        private val material = run {
            val salt = p.randomBytes(16)
            val wrapped = suite.seal(Context("master-key"), suite.kekPasswordSecret(secretKey, password, salt), mk)
            val identity = suite.seal(Context("identity-priv"), mk, concat(lp(p.randomBytes(32)), lp(p.randomBytes(32))))
            AccountKeyMaterial(userId, AccountKeyMaterial.SuiteId.v1, listOf(MasterKeyWrap(MasterKeyWrap.Method.PASSWORD, b64(wrapped), "w0", b64(suite.encodeKdfParams(V1.ARGON, salt)))), b64(identity))
        }
        val changes = mutableListOf<ChangePasswordRequest>()
        var erased = 0
        /** What the account's own `passwordChanged` does on this device: its saved key forgotten. */
        var trustTaken: () -> Unit = {}
        override fun masterKey() = mk.copyOf()
        override suspend fun keyMaterial() = material
        override suspend fun challenge(email: String) = LoginChallenge(b64(ByteArray(32)), b64(ByteArray(16)))
        override suspend fun changePassword(body: ChangePasswordRequest) = FlowReply(204).also { changes += body }
        override suspend fun passwordChanged() = trustTaken()
        override suspend fun requestEmailChange(body: EmailChangeRequest) = FlowReply(202)
        /** Whether the played sign-in took a new address: the address flow offers the kit only then (inbox #1010). */
        var taken = false
        override val addressTaken get() = taken
        override suspend fun erasurePreview() = FlowReply(200) to AccountErasurePreview(listOf(ErasureTenant("ta", "A", emptyList())))
        override suspend fun memberNames(tenantId: String, userIds: List<String>) = emptyMap<String, String>()
        override suspend fun holdsModerationKey() = false
        override suspend fun erase(body: EraseOwnAccountRequest) = FlowReply(200)
        override suspend fun erased() { erased++ }
        fun typedKey() = SecretKeyText.format(p, secretKey)
        /** The account's kit as a pasted `veyqkit:` token (the web's paste, a kit's text copied). */
        fun kitToken() = me.raddatz.veyq.mobile.crypto.EmergencyKit.write(p, email, secretKey, null)
    }

    /** The rotation's engine as the test plays it: the gate passes for [Played]'s key, the publish answers every scope secured. */
    private class Engine(private val right: ByteArray) : KeyRotation.Engine {
        var published = 0
        override suspend fun holdsModerationKey() = false
        override suspend fun prove(password: String, secretKey: ByteArray) = if (secretKey.contentEquals(right)) null else ColdCredential.Refusal.REJECTED
        override suspend fun resume(password: String, secretKey: ByteArray) = emptyList<RotationScopeState>()
        override suspend fun publish(password: String, secretKey: ByteArray, newSecretKey: ByteArray, newPassword: String?): KeyRotation.Published {
            published++
            return KeyRotation.Published(false, listOf(RotationScopeState("s1", RotationScopeState.State.secured)), emptyList())
        }
        override suspend fun linksToRenew(names: KeyRotation.ScopeNames) = listOf(KeyRotation.RenewLink("share-1", "s1"))
    }

    /** One's own profile as the test plays it: a username, no tenant key (the name and picture disabled). */
    private class Profile : ProfileModel.Door {
        override val tenantId = "ta"
        override val userId = "u1"
        val saved = mutableListOf<ProfileUpdateRequest>()
        override suspend fun profile() = MemberProfile("kev", 0, false, false, null, null)
        override suspend fun tenantKey(fresh: Boolean): Pair<ByteArray, Long>? = null
        override suspend fun update(body: ProfileUpdateRequest) = ProfileModel.Reply(204).also { saved += body }
        override suspend fun setAvatar(body: SetAvatarRequest) = ProfileModel.Reply(204)
        override suspend fun clearAvatar() = ProfileModel.Reply(204)
        override suspend fun peopleChanged() {}
        override fun picture(): ByteArray? = null
    }

    private fun bridge(
        played: Played,
        engine: Engine,
        profile: Profile,
        door: Door = Door(),
        settings: Boolean = false,
        breach: () -> me.raddatz.veyq.mobile.account.BreachCheck = { me.raddatz.veyq.mobile.account.BreachCheck.none() },
    ) = Bridge {
        val hidden = Hidden({}, emptySet())
        Composition(
            SessionModel(door, hidden, RecentUses({}, emptyList())), hidden, JvmFiles,
            settings = { session, account, separator ->
                if (settings) AccountSettings(Page(), HeldBy(session, account), played.email, separator) { 0L } else null
            },
            accountFlows = { _, _, _ ->
                AccountFlows(
                    { PasswordChange(played, p, breach()) }, { AddressChange(played, p) },
                    { KeyRotation(engine, p, played.email, played.server, breach()) }, { AccountErasure(played, p) },
                )
            },
        ) { _ ->
            Tenant(
                shell = null, pot = null, me = "u1", pictures = kotlinx.coroutines.flow.MutableStateFlow(emptyMap()), loadPictures = {},
                ownProfile = { ProfileModel(profile, suite, null) },
            )
        }
    }

    private suspend fun Bridge<*, *>.signedIn() {
        val gate = watching(Watched.Session)
        assertEquals(Answer.Done, ask(Call.SessionStart))
        assertEquals(Answer.Done, ask(Call.SessionSignIn("a.example", "p@a.example", "right", "A3-SECRET")))
        gate.until { (it as Update.Session).gate as? Gate.Signed }
        unwatch(gate.id)
    }

    @Test
    fun eachFlowRunsThroughItsCallsAndGoesWithItsTenant() = runBlocking {
        val played = Played()
        val engine = Engine(played.secretKey)
        val profile = Profile()
        val b = bridge(played, engine, profile)
        b.signedIn()
        val connection = "a.example#u1"
        val dir = Files.createTempDirectory("kit")

        // one's own profile: read at once, the username saved
        val prof = (b.ask(Call.ProfileOpen) as Answer.ProfileOpened).profile
        val profWatch = b.watching(Watched.Profile(prof))
        profWatch.until { u -> (u as Update.Profile).shown?.takeIf { it.loaded } }
        assertEquals(Answer.Done, b.ask(Call.ProfileUsername(prof, "Kevin")))
        assertEquals(Answer.Done, b.ask(Call.ProfileSave(prof)))
        assertEquals("kevin", profile.saved.single().username)

        // the password: a mismatch refused, then changed with the typed secrets
        val pw = (b.ask(Call.PasswordChangeOpen(connection)) as Answer.FlowOpened).flow
        val pwWatch = b.watching(Watched.PasswordChange(pw))
        assertEquals(Answer.Done, b.ask(Call.PasswordChangeRun(pw, played.password, "a much better one", "a much better 1", played.typedKey())))
        pwWatch.until { u -> (u as Update.PasswordChange).shown?.takeIf { it.refusal == Msg.PASSWORD_CHANGE_MISMATCH } }
        assertEquals(Answer.Done, b.ask(Call.PasswordChangeRun(pw, played.password, "a much better one", "a much better one", played.typedKey()), kotlin.time.Duration.parse("60s")))
        pwWatch.until(kotlin.time.Duration.parse("60s")) { u -> (u as Update.PasswordChange).shown?.takeIf { it.done } }
        assertEquals(1, played.changes.size)

        // the address's kit: none before the sign-in took the new address (inbox #1010)
        val early = (b.ask(Call.AddressChangeOpen(connection)) as Answer.FlowOpened).flow
        assertEquals(Answer.Done, b.ask(Call.AddressChangeKit(early, played.password, played.typedKey()), kotlin.time.Duration.parse("60s")))
        assertEquals(Answer.Done, b.ask(Call.AddressChangeClose(early)))
        // then written by the core at the path the dialog gave, "I saved it" only after
        played.taken = true
        val address = (b.ask(Call.AddressChangeOpen(connection)) as Answer.FlowOpened).flow
        val kit = (b.ask(Call.AddressChangeKit(address, played.password, played.typedKey()), kotlin.time.Duration.parse("60s")) as Answer.KitOpened).kit
        val kitWatch = b.watching(Watched.Kit(kit))
        assertEquals(Answer.Done, b.ask(Call.KitConfirm(kit, true)))
        kitWatch.until { u -> (u as Update.Kit).shown?.takeIf { !it.confirmed } }
        val path = dir.resolve("veyq-emergency-kit-p@a.example.pdf")
        assertEquals(Answer.Done, b.ask(Call.KitSave(kit, path.toString(), "de", 20_775)))
        assertTrue(Files.readAllBytes(path).decodeToString(0, 5) == "%PDF-", "the kit was not written where the dialog said")
        assertEquals(Answer.Done, b.ask(Call.KitConfirm(kit, true)))
        kitWatch.until { u -> (u as Update.Kit).shown?.takeIf { it.saved && it.confirmed } }

        // the rotation: the gate, its kit step, no publish before it is ready, then done
        val rot = (b.ask(Call.KeyRotationOpen(connection)) as Answer.FlowOpened).flow
        val rotWatch = b.watching(Watched.KeyRotation(rot))
        rotWatch.until { u -> (u as Update.KeyRotation).shown?.takeIf { it.step == KeyRotation.Step.GATE } }
        assertEquals(Answer.Done, b.ask(Call.KeyRotationChooseNewPassword(rot, false)))
        val rotKit = (b.ask(Call.KeyRotationProve(rot, played.password, played.typedKey()), kotlin.time.Duration.parse("60s")) as Answer.KitOpened).kit
        assertEquals(Answer.Done, b.ask(Call.KeyRotationRun(rot)))
        assertEquals(0, engine.published, "published before the kit was saved")
        assertEquals(Answer.Done, b.ask(Call.KitSave(rotKit, dir.resolve("rotated.pdf").toString(), "en", 20_775)))
        assertEquals(Answer.Done, b.ask(Call.KitConfirm(rotKit, true)))
        b.ask(Call.KeyRotationRun(rot))
        rotWatch.until { u -> (u as Update.KeyRotation).shown?.takeIf { it.done } }
        assertEquals(1, engine.published)
        // the done rotation outlived the tenant the bridge opened anew on the new keys (inbox #984): a new watch still
        // shows its done step, and its links are read anew through it (inbox #977)
        assertEquals(true, (b.watching(Watched.KeyRotation(rot)).next() as Update.KeyRotation).shown?.done, "the done rotation closed with the tenant")
        assertEquals(Answer.Done, b.ask(Call.KeyRotationRefreshLinks(rot)))
        assertEquals(listOf("share-1"), rotWatch.until { u -> (u as Update.KeyRotation).shown?.links?.takeIf { it.isNotEmpty() } }.map { it.shareId })

        // the erasure, opened but not run: its preview read
        val erase = (b.ask(Call.ErasureOpen(connection)) as Answer.FlowOpened).flow
        val eraseWatch = b.watching(Watched.Erasure(erase))
        eraseWatch.until { u -> (u as Update.Erasure).shown?.takeIf { it.loaded } }

        // the tenant closes (a sign-out): every flow dropped, each watch delivers null, each call refused
        val rotationAfter = b.watching(Watched.KeyRotation(rot))
        assertEquals(Answer.Done, b.ask(Call.SessionSignOut(connection)))
        profWatch.until { u -> (u as Update.Profile).takeIf { it.shown == null } }
        pwWatch.until { u -> (u as Update.PasswordChange).takeIf { it.shown == null } }
        kitWatch.until { u -> (u as Update.Kit).takeIf { it.shown == null } }
        rotationAfter.until { u -> (u as Update.KeyRotation).takeIf { it.shown == null } }
        eraseWatch.until { u -> (u as Update.Erasure).takeIf { it.shown == null } }
        val gone = Answer.Refused(Answer.Why.NO_SUCH_FLOW)
        assertEquals(gone, b.ask(Call.KeyRotationRun(rot)), "a rotation outlived its account's sign-out")
        assertEquals(gone, b.ask(Call.PasswordChangeRun(pw, "a", "b", "b", "c")))
        assertEquals(gone, b.ask(Call.KitSave(kit, dir.resolve("again.pdf").toString(), "en", 1)))
        assertEquals(gone, b.ask(Call.ErasureRun(erase, "pw")))
        assertEquals(gone, b.ask(Call.ProfileSave(prof)))
        assertEquals(Update.KeyRotation(rot, null), b.watching(Watched.KeyRotation(rot)).next())
        dir.toFile().deleteRecursively()
        b.shutdown()
    }

    /**
     * R4 (cr-app-account-rest): a `veyqkit:` token pasted into a flow's Secret Key field is that kit's key, as at the
     * sign-in — the web's paste swap. Written first: red while the bridge read the field as a bare key only, the token
     * becoming 32 zero bytes the floor refused as a wrong key.
     */
    @Test
    fun aPastedKitTokenOpensEachFlow() = runBlocking {
        val played = Played()
        val b = bridge(played, Engine(played.secretKey), Profile())
        b.signedIn()
        val c = "a.example#u1"
        val long = kotlin.time.Duration.parse("60s")
        val token = played.kitToken()

        val pw = (b.ask(Call.PasswordChangeOpen(c)) as Answer.FlowOpened).flow
        val pwWatch = b.watching(Watched.PasswordChange(pw))
        assertEquals(Answer.Done, b.ask(Call.PasswordChangeRun(pw, played.password, "a much better one", "a much better one", token), long))
        val changed = pwWatch.until(long) { u -> (u as Update.PasswordChange).shown?.takeIf { it.done || it.refusal != null } }
        assertTrue(changed.done, "the password change refused the pasted kit: ${changed.refusal}")

        val address = (b.ask(Call.AddressChangeOpen(c)) as Answer.FlowOpened).flow
        val addressWatch = b.watching(Watched.AddressChange(address))
        assertEquals(Answer.Done, b.ask(Call.AddressChangeRequest(address, "new@b.example", played.password, token), long))
        val sent = addressWatch.until(long) { u -> (u as Update.AddressChange).shown?.takeIf { it.sentTo != null || it.refusal != null } }
        assertEquals("new@b.example", sent.sentTo, "the address change refused the pasted kit: ${sent.refusal}")

        played.taken = true
        val kitFlow = (b.ask(Call.AddressChangeOpen(c)) as Answer.FlowOpened).flow
        assertTrue(b.ask(Call.AddressChangeKit(kitFlow, played.password, token), long) is Answer.KitOpened, "the address's kit refused the pasted kit")

        val rot = (b.ask(Call.KeyRotationOpen(c)) as Answer.FlowOpened).flow
        b.watching(Watched.KeyRotation(rot)).until { u -> (u as Update.KeyRotation).shown?.takeIf { it.step == KeyRotation.Step.GATE } }
        assertEquals(Answer.Done, b.ask(Call.KeyRotationChooseNewPassword(rot, false)))
        assertTrue(b.ask(Call.KeyRotationProve(rot, played.password, token), long) is Answer.KitOpened, "the rotation's gate refused the pasted kit")
        b.shutdown()
    }

    /**
     * ADR 0151 decisions 3 and 7 over the bridge: a kit file read into a flow's intake by its path (`Kit.Read`), shown as
     * its address only and cleared (`Kit.Clear`); a kit scan routed to the flow (`FLOW_KIT`); the new password's check on
     * leaving the field (`*.Check`), a hit stopping the change until `*.UseAnyway`, an edit taking it back. Every update
     * of the session, as the host gets it, is scanned for the Secret Key in each of its forms, the kit's token, the new
     * password and its SHA-1: none. Bite: the check's password serialized with its state → red here.
     */
    @Test
    fun aFlowSessionTakesTheKitByPathAndChecksTheNewPasswordAndSendsNeither() = runBlocking {
        val played = Played()
        val next = "a much better one"
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(next.encodeToByteArray()).joinToString("") { "%02X".format(it) }
        val ranges = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { r ->
            ranges += r.url.toString()
            respond("${hash.drop(5)}:12\r\n", io.ktor.http.HttpStatusCode.OK)
        })
        val breach = { me.raddatz.veyq.mobile.account.BreachCheck(me.raddatz.veyq.mobile.session.OutsideHttp(client, "http://a.example")) { "https://range.example/range/" } }
        val b = bridge(played, Engine(played.secretKey), Profile(), breach = breach)
        b.signedIn()
        val c = "a.example#u1"
        val long = kotlin.time.Duration.parse("60s")
        val dir = Files.createTempDirectory("flowkit")
        val kitFile = dir.resolve("kit.txt").also { Files.writeString(it, "my kit: ${played.kitToken()}\n") }
        val noKit = dir.resolve("other.txt").also { Files.writeString(it, "no kit in here") }

        val pw = (b.ask(Call.PasswordChangeOpen(c)) as Answer.FlowOpened).flow
        val raw = b.watching(Watched.PasswordChange(pw))
        val w = b.watching(Watched.PasswordChange(pw))
        assertEquals(Answer.Refused(Answer.Why.NO_KIT), b.ask(Call.KitRead(pw, noKit.toString()), long))
        assertEquals(Answer.Done, b.ask(Call.KitRead(pw, kitFile.toString()), long))
        val held = w.until { u -> (u as Update.PasswordChange).kit?.kit }
        assertEquals(me.raddatz.veyq.mobile.account.KeyIntake.HeldKit(played.email, null, me.raddatz.veyq.mobile.account.KitImport.ServerFrom.NONE), held)
        assertEquals(Answer.Done, b.ask(Call.KitClear(pw)))
        w.until { u -> (u as Update.PasswordChange).kit?.takeIf { it.kit == null } }
        assertEquals(Answer.Done, b.ask(Call.KitRead(pw, kitFile.toString()), long))
        w.until { u -> (u as Update.PasswordChange).kit?.kit }

        assertEquals(Answer.Done, b.ask(Call.PasswordChangeCheck(pw, next), long))
        val hit = w.until { u -> (u as Update.PasswordChange).breach?.takeIf { it.kind == me.raddatz.veyq.mobile.account.BreachCheck.BreachKind.HIT } }
        assertEquals(12, hit.count)
        assertEquals(listOf("https://range.example/range/${hash.take(5)}"), ranges.toList())
        assertEquals(Answer.Done, b.ask(Call.PasswordChangeRun(pw, played.password, next, next, ""), long))
        assertTrue(played.changes.isEmpty(), "a breached password was submitted without Use it anyway")
        assertEquals(Answer.Done, b.ask(Call.PasswordChangeUseAnyway(pw, true)))
        w.until { u -> (u as Update.PasswordChange).breach?.takeIf { it.kind == me.raddatz.veyq.mobile.account.BreachCheck.BreachKind.ACCEPTED } }
        assertEquals(Answer.Done, b.ask(Call.PasswordChangeRun(pw, played.password, next, next, ""), long))
        w.until(long) { u -> (u as Update.PasswordChange).shown?.takeIf { it.done } }
        assertEquals(1, played.changes.size, "the held kit's key did not change the password")
        assertEquals(1, ranges.size, "the accepted password was queried again")
        assertEquals(Answer.Done, b.ask(Call.PasswordChangeEdited(pw)))
        w.until { u -> (u as Update.PasswordChange).breach?.takeIf { it.kind == me.raddatz.veyq.mobile.account.BreachCheck.BreachKind.IDLE } }

        // the rotation's gate: its new password checked, its kit scanned into its own intake
        val rot = (b.ask(Call.KeyRotationOpen(c)) as Answer.FlowOpened).flow
        val rotRaw = b.watching(Watched.KeyRotation(rot))
        assertEquals(Answer.Done, b.ask(Call.KeyRotationCheck(rot, next), long))
        b.watching(Watched.KeyRotation(rot)).until { u -> (u as Update.KeyRotation).breach?.takeIf { it.kind == me.raddatz.veyq.mobile.account.BreachCheck.BreachKind.HIT } }
        assertEquals(Answer.Done, b.ask(Call.KeyRotationUseAnyway(rot, true)))
        assertEquals(Answer.Done, b.ask(Call.KeyRotationEdited(rot)))
        assertTrue(b.ask(Call.ScanOpen(ScanKind.FLOW_KIT, link = rot)) is Answer.ScanOpened)
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_FLOW), b.ask(Call.ScanOpen(ScanKind.FLOW_KIT, link = 999_999)))
        assertEquals(Answer.Refused(Answer.Why.NO_SUCH_FLOW), b.ask(Call.KitRead(999_999, kitFile.toString())))

        assertEquals(Answer.Done, b.ask(Call.PasswordChangeClose(pw)))
        assertEquals(Answer.Done, b.ask(Call.KeyRotationClose(rot)))
        w.until { u -> (u as Update.PasswordChange).takeIf { it.shown == null } }
        val sent = mutableListOf<String>()
        for (watch in listOf(raw, rotRaw)) while (true) sent += watch.pending() ?: break
        assertTrue(sent.isNotEmpty())
        val key = played.secretKey
        val canaries = listOf(
            SecretKeyText.format(p, key), SecretKeyText.format(p, key).replace("-", ""), me.raddatz.veyq.mobile.crypto.hex(key), b64(key),
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key), played.kitToken(), "veyqkit:", next, hash, hash.drop(5),
        )
        for (u in sent) for (canary in canaries) assertTrue(canary !in u, "an update carries a secret: $u")
        dir.toFile().deleteRecursively()
        b.shutdown()
    }

    /** A Settings page's server: one family, this device's; no wraps, no register. */
    private class Page : AccountSettings.Door() {
        override val deviceId = "dev-here"
        override val fallbackLabel = "Windows 11"
        override fun myWrapId() = null
        override suspend fun email() = "p@a.example" to true
        override suspend fun families() = listOf(me.raddatz.veyq.mobile.api.models.SessionFamily("f-here", "2026-11-01T10:00:00Z", "2026-11-12T09:30:00Z", true))
        override suspend fun revoke(family: String) {}
        override suspend fun revokeOthers() {}
        override suspend fun savedKeys() = emptyList<String>()
        override suspend fun removeSavedKey(wrap: String) {}
        override suspend fun capabilities() = emptySet<String>()
    }

    /** This device's trust as the session holds it, as the core's own `AccountSettings.Held`. */
    private class HeldBy(private val session: SessionModel<Acc, String>, private val account: Acc) : AccountSettings.Here<Acc, String> {
        override fun trust() = session.managed().first { it.account == account }.let { AccountSettings.Trust(it.trusted, it.level, it.levels) }
        override suspend fun stay(level: StaySignedIn) = session.stay(account, level)
        override suspend fun trustWithPassword(password: String) = session.trustWithPassword(account, password)
        override suspend fun stopTrusting() = session.forgetDevice(account)
        override suspend fun signOut() = session.signOut(account)
    }

    /**
     * Inbox #1009: a password change takes this device's saved key, and the account's Settings page open beside it reads
     * anew — its trust row no longer says the device stays signed in, and offers each level and the password trust.
     */
    @Test
    fun aPasswordChangeRereadsTheAccountsSettingsPage() = runBlocking {
        val played = Played()
        val door = Door().apply {
            levels = setOf(StaySignedIn.LOGIN)
            record = TrustedDevice("http://a.example", played.email, "u1", TrustedDevice.Method.DEVICE_KEY, "w0", "alias", StaySignedIn.LOGIN)
        }
        played.trustTaken = { door.record = null }
        val b = bridge(played, Engine(played.secretKey), Profile(), door, settings = true)
        b.signedIn()
        val id = (b.ask(Call.SettingsOpen("a.example#u1")) as Answer.SettingsOpened).settings
        val page = b.watching(Watched.Settings(id))
        val before = page.until { (it as Update.Settings).shown?.takeIf { s -> s.families != null && !s.working } }
        assertEquals(TrustedDevice.Method.DEVICE_KEY, before.trusted)

        val pw = (b.ask(Call.PasswordChangeOpen("a.example#u1")) as Answer.FlowOpened).flow
        val to = "a much better one"
        assertEquals(Answer.Done, b.ask(Call.PasswordChangeRun(pw, played.password, to, to, played.typedKey()), kotlin.time.Duration.parse("60s")))
        val after = page.until { (it as Update.Settings).shown?.takeIf { s -> s.trusted == null && !s.working } }
        assertEquals(null, after.level)
        assertEquals(listOf(StaySignedIn.LOGIN, null), AccountSettings.Trust(after.trusted, after.level, after.levels).changes)
        b.shutdown()
    }

    @Test
    fun theErasureSignsTheAccountOutHere() = runBlocking {
        val played = Played()
        val b = bridge(played, Engine(played.secretKey), Profile())
        b.signedIn()
        val erase = (b.ask(Call.ErasureOpen("a.example#u1")) as Answer.FlowOpened).flow
        val watch = b.watching(Watched.Erasure(erase))
        watch.until { u -> (u as Update.Erasure).shown?.takeIf { it.loaded } }
        b.ask(Call.ErasureRun(erase, played.password), kotlin.time.Duration.parse("60s"))
        assertEquals(1, played.erased, "the device did not forget the erased account")
        assertTrue(b.gateNow() !is Gate.Signed, "the erased account stayed signed in")
        b.shutdown()
    }
}
