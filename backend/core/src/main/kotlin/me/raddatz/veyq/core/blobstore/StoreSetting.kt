package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretKeyState
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import me.raddatz.veyq.core.crypto.SealedSecret
import me.raddatz.veyq.core.crypto.SealedValueFingerprints
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.LaunchMode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The object-store setting (ADR 0089): one row once the Server Owner has set a store, none before. [bundled] is a
 * reference to the deployment's [BundledStoreHint] — its values are never copied here, so every connection column
 * is null for it. The secrets are sealed under the config-secret KEK (ADR 0050), each with the fingerprint of the
 * key that sealed it (decision 4). `check*` is the last check of THIS setting, cleared whenever it changes.
 */
@Entity
@Table(name = "veyq_store_setting")
@DynamicUpdate
class StoreSettingEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = SINGLETON_ID

    @Column(name = "bundled", nullable = false)
    var bundled: Boolean = false

    @Column(name = "store_type", length = 16)
    var storeType: String? = null

    @Column(name = "endpoint", length = 2048)
    var endpoint: String? = null

    @Column(name = "public_endpoint", length = 2048)
    var publicEndpoint: String? = null

    @Column(name = "bucket", length = 63)
    var bucket: String? = null

    @Column(name = "region", length = 64)
    var region: String? = null

    @Column(name = "access_key", length = 256)
    var accessKey: String? = null

    @Column(name = "secret_key_ciphertext")
    var secretKeyCiphertext: String? = null

    @Column(name = "secret_key_nonce")
    var secretKeyNonce: String? = null

    @Column(name = "secret_key_fingerprint", length = 64)
    var secretKeyFingerprint: String? = null

    @Column(name = "garage_admin_endpoint", length = 2048)
    var garageAdminEndpoint: String? = null

    @Column(name = "garage_admin_token_ciphertext")
    var garageAdminTokenCiphertext: String? = null

    @Column(name = "garage_admin_token_nonce")
    var garageAdminTokenNonce: String? = null

    @Column(name = "garage_admin_token_fingerprint", length = 64)
    var garageAdminTokenFingerprint: String? = null

    @Column(name = "check_passed")
    var checkPassed: Boolean? = null

    @Column(name = "checked_at")
    var checkedAt: Instant? = null

    /** The last check's per-step result as JSON ([StoreCheckResult.toJson]); null when not checked since set. */
    @Column(name = "check_steps")
    var checkSteps: String? = null

    companion object : PanacheCompanionBase<StoreSettingEntity, Int> {
        const val SINGLETON_ID = 1
    }
}

/** A sealed store secret, by name — the wire's `InstanceStoreSecret`. */
enum class StoreSecret { SECRET_KEY, GARAGE_ADMIN_TOKEN }

/** A sealed value as stored: ciphertext, nonce, and the fingerprint of the key that sealed it (ADR 0089 decision 4). */
data class SealedValue(val ciphertext: String, val nonce: String, val fingerprint: String)

/**
 * The setting as the database holds it, secrets still sealed. [bundled] ⇒ every other connection field is null.
 */
data class StoreSettingRow(
    val bundled: Boolean,
    val type: StoreType?,
    val endpoint: String?,
    val publicEndpoint: String?,
    val bucket: String?,
    val region: String?,
    val accessKey: String?,
    val secretKey: SealedValue?,
    val garageAdminEndpoint: String?,
    val garageAdminToken: SealedValue?,
)

/**
 * Everything the backend needs to reach a store, secrets open — never persisted, logged or returned. [signingMode]
 * is the bundled hint's for the bundled store; for a store of the operator's own it follows [publicEndpoint]: none
 * ⇒ browsers reach the store at [endpoint] ([SigningMode.INTERNAL_ENDPOINT]), one ⇒ it is signed verbatim
 * ([SigningMode.STORE_ORIGIN]). [SigningMode.TENANT_DOMAIN] needs this deployment's own proxy in front of the store,
 * which only a recipe that brings the store can promise.
 */
data class StoreConnection(
    val bundled: Boolean,
    val type: StoreType,
    val endpoint: String,
    val publicEndpoint: String?,
    val signingMode: SigningMode,
    val bucket: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val garageAdminEndpoint: String?,
    val garageAdminToken: String?,
) {
    override fun toString(): String =
        "StoreConnection(bundled=$bundled, type=$type, endpoint=$endpoint, bucket=$bucket)" // never the secrets
}

/** What the current setting resolves to. */
sealed interface StoreResolution {
    /** No store has been set. */
    data object NotSet : StoreResolution

    /** A store is set but cannot be reached with what this instance holds: [unreadable] secrets sealed under another
     *  (or no) key, or a bundled store whose deployment passes no hint ([hintMissing]). */
    data class Unavailable(val unreadable: List<StoreSecret>, val hintMissing: Boolean = false) : StoreResolution

    data class Ready(val connection: StoreConnection) : StoreResolution
}

/** The setting's persistence. Every write runs in the caller's transaction. */
@ApplicationScoped
class StoreSettingStore(
    private val em: EntityManager,
) : SealedValueFingerprints {

    fun find(): StoreSettingRow? = StoreSettingEntity.findById(StoreSettingEntity.SINGLETON_ID)?.toRow()

    /** Replace the setting with [row] and clear its check — a changed setting has not been checked. */
    fun save(row: StoreSettingRow) {
        val e = StoreSettingEntity.findById(StoreSettingEntity.SINGLETON_ID) ?: StoreSettingEntity()
        e.bundled = row.bundled
        e.storeType = row.type?.name
        e.endpoint = row.endpoint
        e.publicEndpoint = row.publicEndpoint
        e.bucket = row.bucket
        e.region = row.region
        e.accessKey = row.accessKey
        e.secretKeyCiphertext = row.secretKey?.ciphertext
        e.secretKeyNonce = row.secretKey?.nonce
        e.secretKeyFingerprint = row.secretKey?.fingerprint
        e.garageAdminEndpoint = row.garageAdminEndpoint
        e.garageAdminTokenCiphertext = row.garageAdminToken?.ciphertext
        e.garageAdminTokenNonce = row.garageAdminToken?.nonce
        e.garageAdminTokenFingerprint = row.garageAdminToken?.fingerprint
        e.checkPassed = null
        e.checkedAt = null
        e.checkSteps = null
        e.persist()
    }

    /** Record [result] as the current setting's check. A no-op when the setting is gone. */
    fun recordCheck(result: StoreCheckResult) {
        val e = StoreSettingEntity.findById(StoreSettingEntity.SINGLETON_ID) ?: return
        e.checkPassed = result.passed
        e.checkedAt = result.checkedAt
        e.checkSteps = result.toJson()
    }

    /** The current setting's last check, or null when it has not been checked since it was set. */
    fun lastCheck(): StoreCheckResult? {
        val e = StoreSettingEntity.findById(StoreSettingEntity.SINGLETON_ID) ?: return null
        val at = e.checkedAt ?: return null
        val steps = e.checkSteps ?: return null
        return StoreCheckResult.fromJson(e.checkPassed == true, at, steps)
    }

    /** Whether a store is set and its last check passed — the precondition for a tenant (ADR 0089 decision 6). */
    fun checkPassed(): Boolean = StoreSettingEntity.findById(StoreSettingEntity.SINGLETON_ID)?.checkPassed == true

    /**
     * Whether any blob is in the store — a completed blob, or an upload session whose chunks may already be there.
     * Once true, the store's type, endpoint and bucket are fixed (decision 6).
     */
    fun blobsStored(): Boolean =
        (em.createNativeQuery(
            "SELECT CASE WHEN EXISTS (SELECT 1 FROM veyq_blob) OR EXISTS (SELECT 1 FROM veyq_upload_session) " +
                "THEN 1 ELSE 0 END",
        ).singleResult as Number).toInt() == 1

    /** The store secrets' part of the instance-wide config-secret state (ADR 0089 decision 4). */
    override fun sealedFingerprints(): List<String> {
        val e = StoreSettingEntity.findById(StoreSettingEntity.SINGLETON_ID) ?: return emptyList()
        return listOfNotNull(
            e.secretKeyCiphertext?.let { e.secretKeyFingerprint ?: "" },
            e.garageAdminTokenCiphertext?.let { e.garageAdminTokenFingerprint ?: "" },
        )
    }

    private fun StoreSettingEntity.toRow() = StoreSettingRow(
        bundled = bundled,
        type = storeType?.let { StoreType.valueOf(it) },
        endpoint = endpoint,
        publicEndpoint = publicEndpoint,
        bucket = bucket,
        region = region,
        accessKey = accessKey,
        secretKey = sealedValue(secretKeyCiphertext, secretKeyNonce, secretKeyFingerprint),
        garageAdminEndpoint = garageAdminEndpoint,
        garageAdminToken = sealedValue(garageAdminTokenCiphertext, garageAdminTokenNonce, garageAdminTokenFingerprint),
    )

    private fun sealedValue(ciphertext: String?, nonce: String?, fingerprint: String?): SealedValue? =
        if (ciphertext == null || nonce == null) null else SealedValue(ciphertext, nonce, fingerprint ?: "")
}

/**
 * The deployment's bundled-store hint (ADR 0089 decision 3) as a [StoreConnection], or its absence. The hint is
 * environment, read once; choosing the bundled store stores only a reference to it, so a restore onto a fresh
 * install and a lost config-secret key both leave the bundled case reading the hint of the install it runs on.
 */
@ApplicationScoped
class BundledStoreHint(
    config: VeyqConfig,
) {
    private val hint = config.bundledStore()

    /** The bundled store, or null when this deployment passes no complete hint. */
    val connection: StoreConnection? = run {
        val endpoint = hint.endpoint().orElse("").trim()
        val adminEndpoint = hint.adminEndpoint().orElse("").trim()
        val adminToken = hint.adminToken().orElse("")
        if (endpoint.isEmpty() || adminEndpoint.isEmpty() || adminToken.isBlank()) return@run null
        val credentials = BundledStoreCredentials.derive(adminToken)
        val publicEndpoint = hint.publicEndpoint().orElse("").trim().ifEmpty { null }
        StoreConnection(
            bundled = true,
            type = StoreType.GARAGE,
            endpoint = endpoint,
            publicEndpoint = publicEndpoint,
            signingMode = if (publicEndpoint == null) SigningMode.INTERNAL_ENDPOINT else hint.signingMode(),
            bucket = hint.bucket(),
            region = hint.region(),
            accessKey = credentials.accessKey,
            secretKey = credentials.secretKey,
            garageAdminEndpoint = adminEndpoint,
            garageAdminToken = adminToken,
        )
    }

    val available: Boolean get() = connection != null
}

/**
 * The bundled store's S3 key pair, **derived** from its admin token rather than passed or stored (ADR 0089 decision
 * 3): veyq imports exactly this pair into the Garage it provisions, so the bundled case needs no credential of its
 * own in the environment and none sealed under the config-secret key — a lost key cannot take the bundled store's
 * media with it. HKDF-SHA256 (RFC 5869) over the token, one `info` per half, in Garage's key shapes: `GK` + 24 hex
 * for the id, 64 hex for the secret. `scripts/instance_backup.py` derives the same pair from the same token for the
 * bundled capture; `BundledStoreCredentialsTest` and its self-test pin one vector on both sides.
 */
data class BundledStoreCredentials(val accessKey: String, val secretKey: String) {
    override fun toString(): String = "BundledStoreCredentials(accessKey=$accessKey)" // never the secret

    companion object {
        private val ACCESS_KEY_INFO = "veyq/v1/bundled-store/access-key-id".toByteArray(Charsets.UTF_8)
        private val SECRET_KEY_INFO = "veyq/v1/bundled-store/secret-access-key".toByteArray(Charsets.UTF_8)

        fun derive(adminToken: String): BundledStoreCredentials {
            val ikm = adminToken.toByteArray(Charsets.UTF_8)
            val id = ConfigSecretSeal.hkdfSha256(ikm, ByteArray(0), ACCESS_KEY_INFO, 12)
            val secret = ConfigSecretSeal.hkdfSha256(ikm, ByteArray(0), SECRET_KEY_INFO, 32)
            return BundledStoreCredentials("GK" + hex(id), hex(secret))
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * The current store setting, resolved — what [SwitchingBlobStore], the app shell's CSP and the tenant ingress all
 * follow (ADR 0089 decision 5). Resolved once and cached; [invalidate] after a committed change makes the next read
 * resolve again. One backend process serves an instance (the recipes run one; the chart's `Recreate` strategy keeps
 * it one), so an in-process invalidation reaches every reader.
 *
 * **A cached resolution is tagged with the generation it was resolved under**, and served only while that generation
 * is current — the same guard as `StoredCapabilitySwitches`. A reader that read the row just before a write committed
 * would otherwise store the old resolution after the write's [invalidate] had run, and the instance would sign against
 * the previous store until the next write or a restart (inbox #445; `StoreSettingsCacheSliceTest` forces that
 * interleaving).
 */
@ApplicationScoped
class StoreSettings(
    private val store: StoreSettingStore,
    private val hint: BundledStoreHint,
    private val seal: ConfigSecretSeal,
    private val keys: ConfigSecretKeyGuard,
) {
    private val hermetic = LaunchMode.current() == LaunchMode.TEST

    private val generation = AtomicLong()

    @Volatile
    private var cached: Cached? = null

    fun current(): StoreResolution {
        val current = generation.get()
        cached?.takeIf { it.generation == current }?.let { return it.resolution }
        return resolve().also { cached = Cached(current, it) }
    }

    /** Forget the cached resolution. Call after the transaction that changed the setting has COMMITTED — before it,
     *  a reader could resolve and cache the old row under the new generation. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    private class Cached(val generation: Long, val resolution: StoreResolution)

    /**
     * The precondition for creating a tenant (ADR 0089 decision 6): a store is set, usable, and its last check passed.
     * Under `%test` with no setting at all the in-memory stub stands in for a store ([SwitchingBlobStore]), so the
     * hermetic suite's thousands of tenants need no check; a test that exercises the gate sets a store first.
     */
    fun readyForTenants(): Boolean = when (current()) {
        is StoreResolution.NotSet -> hermetic
        is StoreResolution.Unavailable -> false
        is StoreResolution.Ready -> QuarkusTransaction.joiningExisting().call { store.checkPassed() }
    }

    /** The bucket the tenant ingress routes for the store: the current store's, else the bundled one's, else the default. */
    fun routeBucket(): String =
        (current() as? StoreResolution.Ready)?.connection?.bucket ?: hint.connection?.bucket ?: DEFAULT_BUCKET

    /** Which stored secrets cannot be opened under the current key — the console's re-entry prompt. */
    fun unreadable(row: StoreSettingRow): List<StoreSecret> = buildList {
        if (row.secretKey != null && keys.stateOf(row.secretKey.fingerprint) != ConfigSecretKeyState.OK) add(StoreSecret.SECRET_KEY)
        if (row.garageAdminToken != null && keys.stateOf(row.garageAdminToken.fingerprint) != ConfigSecretKeyState.OK) {
            add(StoreSecret.GARAGE_ADMIN_TOKEN)
        }
    }

    private fun resolve(): StoreResolution {
        val row = QuarkusTransaction.joiningExisting().call { store.find() } ?: return StoreResolution.NotSet
        if (row.bundled) return hint.connection?.let { StoreResolution.Ready(it) }
            ?: StoreResolution.Unavailable(emptyList(), hintMissing = true)
        val unreadable = unreadable(row)
        if (unreadable.isNotEmpty()) return StoreResolution.Unavailable(unreadable)
        val type = row.type ?: return StoreResolution.NotSet
        val publicEndpoint = row.publicEndpoint?.ifBlank { null }
        return StoreResolution.Ready(
            StoreConnection(
                bundled = false,
                type = type,
                endpoint = row.endpoint.orEmpty(),
                publicEndpoint = publicEndpoint,
                signingMode = if (publicEndpoint == null) SigningMode.INTERNAL_ENDPOINT else SigningMode.STORE_ORIGIN,
                bucket = row.bucket.orEmpty(),
                region = row.region.orEmpty(),
                accessKey = row.accessKey.orEmpty(),
                secretKey = row.secretKey?.let { open(it) }.orEmpty(),
                garageAdminEndpoint = row.garageAdminEndpoint,
                garageAdminToken = row.garageAdminToken?.let { open(it) },
            ),
        )
    }

    private fun open(value: SealedValue): String = seal.unseal(SealedSecret(value.ciphertext, value.nonce))

    companion object {
        const val DEFAULT_BUCKET = "blobs"
    }
}
