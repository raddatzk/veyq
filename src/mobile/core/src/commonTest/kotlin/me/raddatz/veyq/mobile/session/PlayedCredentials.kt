package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.AccountErasurePreview
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.ChangePasswordRequest
import me.raddatz.veyq.mobile.api.models.EmailChangeRequest
import me.raddatz.veyq.mobile.api.models.EraseOwnAccountRequest
import me.raddatz.veyq.mobile.api.models.LoginChallenge
import me.raddatz.veyq.mobile.api.models.MasterKeyWrap
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.concat
import me.raddatz.veyq.mobile.crypto.lp

/**
 * An account the credential flows' tests play: its Master Key, an identity sealed under it, its PASSWORD floor under
 * [password] and [secretKey], and every request the flows sent. Each answer is the played server's, set per test.
 */
internal class PlayedCredentials(
    val p: Primitives,
    val password: String = "correct horse battery",
    override val email: String = "p@a.example",
) : CredentialDoor {
    val suite = V1(p)
    override val userId = "0e000000-0000-4000-a000-000000000001"
    override val server = "http://a.example"
    val secretKey: ByteArray = p.randomBytes(32)
    val instanceSalt: ByteArray = p.randomBytes(16)
    var mk: ByteArray? = p.randomBytes(32)
    private val identity = suite.seal(Context("identity-priv"), mk!!, concat(lp(p.randomBytes(32)), lp(p.randomBytes(32))))
    var material: AccountKeyMaterial = materialFor(mk!!, password, secretKey)

    /** The floor of [masterKey] under [pw] and [sk], with the identity sealed under [masterKey]. */
    fun materialFor(masterKey: ByteArray, pw: String, sk: ByteArray, sealedIdentity: ByteArray = identity): AccountKeyMaterial {
        val salt = p.randomBytes(16)
        val kek = suite.kekPasswordSecret(sk, pw, salt)
        val wrapped = suite.seal(Context("master-key"), kek, masterKey)
        return AccountKeyMaterial(
            userId, AccountKeyMaterial.SuiteId.v1,
            listOf(MasterKeyWrap(MasterKeyWrap.Method.PASSWORD, b64(wrapped), "w-floor", b64(suite.encodeKdfParams(V1.ARGON, salt)))),
            b64(sealedIdentity),
        )
    }

    fun secretKeyCopy(): ByteArray = secretKey.copyOf()

    var nonce: ByteArray = p.randomBytes(32)
    var answer: FlowReply = FlowReply(204)
    val changes = mutableListOf<ChangePasswordRequest>()
    val emailChanges = mutableListOf<EmailChangeRequest>()
    val erasures = mutableListOf<EraseOwnAccountRequest>()
    var passwordChangedCalls = 0
    var erasedCalls = 0
    var preview: Pair<FlowReply, AccountErasurePreview?> = FlowReply(200) to AccountErasurePreview(emptyList())
    var moderator = false
    var names: Map<String, String> = emptyMap()

    override fun masterKey(): ByteArray? = mk?.copyOf()
    override suspend fun keyMaterial(): AccountKeyMaterial = material
    override suspend fun challenge(email: String) = LoginChallenge(b64(nonce), b64(instanceSalt))
    override suspend fun changePassword(body: ChangePasswordRequest): FlowReply = answer.also { changes += body }
    override suspend fun passwordChanged() { passwordChangedCalls++ }
    override suspend fun requestEmailChange(body: EmailChangeRequest): FlowReply = answer.also { emailChanges += body }
    /** Whether the played sign-in took a new address ([CredentialDoor.addressTaken]), and the address the server holds. */
    var taken = false
    var held: String? = null
    override val addressTaken: Boolean get() = taken
    override suspend fun heldEmail(): String = held ?: email
    override suspend fun erasurePreview() = preview
    override suspend fun memberNames(tenantId: String, userIds: List<String>) = names.filterKeys { it in userIds }
    override suspend fun holdsModerationKey() = moderator
    override suspend fun erase(body: EraseOwnAccountRequest): FlowReply = answer.also { erasures += body }
    override suspend fun erased() { erasedCalls++ }

    /** The auth key's public half of [pw] under [address]'s salt, as the server would hold it. */
    fun authPublic(pw: String, address: String = email): ByteArray {
        val seed = suite.authSeed(pw, suite.authSalt(instanceSalt, address))
        return p.ed25519Public(seed).also { seed.fill(0) }
    }
}
