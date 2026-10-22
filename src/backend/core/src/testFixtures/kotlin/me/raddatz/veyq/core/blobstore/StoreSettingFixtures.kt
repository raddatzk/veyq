package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.config.VeyqConfig.SigningMode
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusMock
import jakarta.enterprise.inject.Vetoed
import java.time.Instant

/**
 * Puts the object-store setting (ADR 0089) into a hermetic `%test` database and takes it out again. The `%test`
 * H2 instance is JVM-shared across test classes, so a test that stores a setting clears it in its `finally` — an
 * instance with no setting is the state every other class assumes (its store is the in-memory stub).
 *
 * Every write forgets [StoreSettings]' cached resolution after it commits, the way the handler does, so the
 * [SwitchingBlobStore] follows the new row on its next call.
 */
object StoreSettingFixtures {

    /** The bundled store chosen — a reference to the deployment's hint. `%test` passes none, so on its own this
     *  resolves to `Unavailable(hintMissing)`; [useBundled] supplies one. */
    fun chooseBundled() = set(StoreSettingRow(true, null, null, null, null, null, null, null, null, null))

    /**
     * The bundled store chosen AND offered: [ReadyBundledHint] installed for the calling test method (`QuarkusMock`,
     * so `:core`'s test JVM boots no further profile), then [chooseBundled] — whose invalidate makes the next read
     * resolve to `Ready` with [bucket]. Only inside a `@QuarkusTest` method; the mock ends with it, the row does not,
     * so the caller still [clear]s in its `finally`.
     */
    fun useBundled(bucket: String = "blobs") {
        val config = Arc.container().instance(VeyqConfig::class.java).get()
        QuarkusMock.installMockForType(ReadyBundledHint(config, bucket), BundledStoreHint::class.java)
        chooseBundled()
    }

    /** An S3-compatible store of the operator's own. */
    fun chooseS3(
        endpoint: String = "http://s3.invalid:9000",
        publicEndpoint: String? = null,
        bucket: String = "blobs",
        secretKey: SealedValue? = null,
    ) = set(StoreSettingRow(false, StoreType.S3, endpoint, publicEndpoint, bucket, "us-east-1", "access", secretKey, null, null))

    /** An S3-compatible store whose [secretKey] is sealed under the running config-secret key, as the console does. */
    fun chooseS3Sealing(secretKey: String, endpoint: String, accessKey: String, bucket: String, region: String) {
        val seal = Arc.container().instance(ConfigSecretSeal::class.java).get()
        val sealed = seal.seal(secretKey)
        val fingerprint = Arc.container().instance(ConfigSecretKeyGuard::class.java).get().currentFingerprint()!!
        set(StoreSettingRow(false, StoreType.S3, endpoint, null, bucket, region, accessKey, SealedValue(sealed.ciphertext, sealed.nonce, fingerprint), null, null))
    }

    /** Save [row] as the setting IN USE, skipping the check that would otherwise put it there. */
    fun set(row: StoreSettingRow) {
        QuarkusTransaction.requiringNew().run { store().save(row) }
        settings().invalidate()
    }

    /** Save [row] as the PENDING setting (ADR 0089 decision 5) — beside the one in use, which it does not change. */
    fun setPending(row: StoreSettingRow) {
        QuarkusTransaction.requiringNew().run { store().savePending(row) }
    }

    /** Record a check of the current setting — passed, with every step passing — without any network. */
    fun recordPassedCheck() {
        val steps = StoreCheckStep.entries.map { StoreCheckStepResult(it, StoreCheckOutcome.PASSED, null) }
        QuarkusTransaction.requiringNew().run { store().recordCheck(StoreCheckResult(true, Instant.now(), steps)) }
    }

    fun clear() {
        QuarkusTransaction.requiringNew().run { StoreSettingEntity.deleteAll() }
        settings().invalidate()
    }

    private fun store(): StoreSettingStore = Arc.container().instance(StoreSettingStore::class.java).get()
    private fun settings(): StoreSettings = Arc.container().instance(StoreSettings::class.java).get()
}

/**
 * A deployment that passes a complete bundled-store hint, with [bucket] as its `veyq.bundled-store.bucket`. Every
 * value is inert: nothing in a test that uses it reaches the store — it exists so the setting in use can resolve to
 * the bundled store under `%test`, which passes no hint ([StoreSettingFixtures.useBundled]). `@Vetoed`, because the
 * scope it inherits would otherwise make it a second candidate for every `BundledStoreHint` injection point.
 */
@Vetoed
class ReadyBundledHint(config: VeyqConfig, bucket: String) : BundledStoreHint(config) {
    override val connection: StoreConnection? = StoreConnection(
        bundled = true,
        type = StoreType.GARAGE,
        endpoint = "http://garage.invalid:3900",
        publicEndpoint = null,
        signingMode = SigningMode.INTERNAL_ENDPOINT,
        bucket = bucket,
        region = "garage",
        accessKey = "GK000000000000000000000000",
        secretKey = "inert",
        garageAdminEndpoint = "http://garage.invalid:3903",
        garageAdminToken = "inert",
    )
}
