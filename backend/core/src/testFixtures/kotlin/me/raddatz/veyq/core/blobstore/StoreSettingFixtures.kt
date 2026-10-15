package me.raddatz.veyq.core.blobstore

import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
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

    /** The bundled store chosen — a reference to the deployment's hint, which a test profile supplies. */
    fun chooseBundled() = set(StoreSettingRow(true, null, null, null, null, null, null, null, null, null))

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

    fun set(row: StoreSettingRow) {
        QuarkusTransaction.requiringNew().run { store().save(row) }
        settings().invalidate()
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
