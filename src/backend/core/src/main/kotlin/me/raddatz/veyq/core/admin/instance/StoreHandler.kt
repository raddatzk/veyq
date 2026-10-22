package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.blobstore.BlobStoreProducer
import me.raddatz.veyq.core.blobstore.BlobStoreStartupCheck
import me.raddatz.veyq.core.blobstore.BundledStoreHint
import me.raddatz.veyq.core.blobstore.SealedValue
import me.raddatz.veyq.core.blobstore.StoreCheck
import me.raddatz.veyq.core.blobstore.StoreCheckOutcome
import me.raddatz.veyq.core.blobstore.StoreCheckResult
import me.raddatz.veyq.core.blobstore.StoreCheckStep
import me.raddatz.veyq.core.blobstore.StoreProvisionerFactory
import me.raddatz.veyq.core.blobstore.StoreProvisioningRunner
import me.raddatz.veyq.core.blobstore.StoreResolution
import me.raddatz.veyq.core.blobstore.StoreSecret
import me.raddatz.veyq.core.blobstore.StoreSettingRow
import me.raddatz.veyq.core.blobstore.StoreSettingStore
import me.raddatz.veyq.core.blobstore.StoreSettings
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.config.VeyqConfig.StoreType
import me.raddatz.veyq.core.crypto.ConfigSecretKeyGuard
import me.raddatz.veyq.core.crypto.ConfigSecretSeal
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.instance.VeyqInstance
import me.raddatz.veyq.core.tenant.TenantIngressProjector
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.jboss.logging.Logger
import java.net.URI

/** The setting as the Server Owner reads it — connection values only; the secrets are flags (ADR 0089 decision 4). */
data class StoreSettingView(
    val bundled: Boolean,
    val type: StoreType,
    val endpoint: String?,
    val publicEndpoint: String?,
    val bucket: String?,
    val region: String?,
    val accessKey: String?,
    val garageAdminEndpoint: String?,
    val secretKeySet: Boolean,
    val garageAdminTokenSet: Boolean,
    val unreadable: List<StoreSecret>,
)

/** A setting saved but not in use until it passes its own check (ADR 0089 decision 5), with that check. */
data class StorePendingView(val setting: StoreSettingView, val lastCheck: StoreCheckResult?)

data class StoreView(
    val bundledAvailable: Boolean,
    val blobsStored: Boolean,
    val setting: StoreSettingView?,
    val lastCheck: StoreCheckResult?,
    val pending: StorePendingView?,
)

/** What the Server Owner asks to store; the wire's `InstanceStoreUpdate`, after the boundary's own validation. */
data class StoreUpdate(
    val bundled: Boolean,
    val type: StoreType?,
    val endpoint: String?,
    val publicEndpoint: String?,
    val bucket: String?,
    val region: String?,
    val accessKey: String?,
    val secretKey: String?,
    val garageAdminEndpoint: String?,
    val garageAdminToken: String?,
)

sealed interface StoreSetOutcome {
    data class Stored(val view: StoreView) : StoreSetOutcome
    data object Invalid : StoreSetOutcome, HttpFailure by Failures.invalidRequest
    data object NoBundledStore : StoreSetOutcome, HttpFailure by httpFailure("no_bundled_store", FailureStatus.CONFLICT)
    data object StoreInUse : StoreSetOutcome, HttpFailure by httpFailure("store_in_use", FailureStatus.CONFLICT)
    data object SecretUnavailable : StoreSetOutcome, HttpFailure by httpFailure("store_secret_unavailable", FailureStatus.SERVICE_UNAVAILABLE)
}

sealed interface StoreCheckRun {
    data class Done(val result: StoreCheckResult) : StoreCheckRun
    data object NotConfigured : StoreCheckRun, HttpFailure by httpFailure("store_not_configured", FailureStatus.CONFLICT)
}

/** What [StoreHandler.adoptBundled] did: saved the bundled store as pending, found it pending from an earlier boot, or
 *  left the instance alone (a setting is saved, or the deployment offers no bundled store). */
enum class StoreAdoption { ADOPTED, RESUMED, NONE }

/**
 * The Server Owner's object-store setting and its check (ADR 0089): what `GET`/`PUT /api/instance/store`,
 * `DELETE /api/instance/store/pending` and `POST /api/instance/store/check` do. The persistence is
 * [StoreSettingStore], the resolved setting in use every reader follows is [StoreSettings], the check itself is
 * [StoreCheck] — this class decides what may be stored and orders the effects.
 *
 * **A write never changes the store in use** (decision 5): it is saved as the pending setting, and only a check that
 * passes promotes it. So a typo in a credential or an endpoint reaches the check, never a running instance's media.
 * The one place the setting in use changes is [check]'s promotion: commit, then forget the cached resolution, then
 * re-project the tenant ingress, so no reader can see the new setting before it is committed or keep the old one after.
 */
@ApplicationScoped
class StoreHandler(
    private val store: StoreSettingStore,
    private val settings: StoreSettings,
    private val hint: BundledStoreHint,
    private val seal: ConfigSecretSeal,
    private val keys: ConfigSecretKeyGuard,
    private val provisioners: StoreProvisionerFactory,
    private val provisioning: StoreProvisioningRunner,
    private val projector: TenantIngressProjector,
    private val config: VeyqConfig,
) {
    // Provisioning is recorded by [check], not here: a pending setting's provisioning says nothing about the store in
    // use until the pending one is promoted.
    private val checker = StoreCheck(
        s3For = { BlobStoreProducer.s3Client(it) },
        signerFor = { BlobStoreProducer.connect(it, config.blobStore().presignTtlSeconds()) },
        provision = { connection -> provisioners.forConnection(connection)?.provision() },
    )

    fun read(): StoreView = QuarkusTransaction.requiringNew().call { view() }

    /**
     * Save [update] as the pending setting — or, equal to the setting in use, discard the pending one. Under the instance
     * singleton's row lock, as [adoptInTransaction] is: a save racing the boot's adoption waits for it and then replaces
     * the adopted store, rather than inserting a second pending row beside it.
     */
    fun set(update: StoreUpdate): StoreSetOutcome = QuarkusTransaction.requiringNew().call<StoreSetOutcome> {
        lockInstance()
        val active = store.find()
        // The form edits what the owner last saved: an omitted secret is kept from the pending setting, else the active.
        val row = when (val built = build(update, store.findPending() ?: active)) {
            is Built.Row -> built.row
            is Built.Refused -> return@call built.outcome
        }
        if (blobsStoredUnderAnotherStore(active, row)) return@call StoreSetOutcome.StoreInUse
        if (row == active) store.discardPending() else store.savePending(row)
        StoreSetOutcome.Stored(view())
    }.also { if (it is StoreSetOutcome.Stored) pendingChanged() }

    /** Drop the pending setting; the one in use, and everything that follows it, is untouched. */
    fun discardPending(): StoreView = QuarkusTransaction.requiringNew().call {
        store.discardPending()
        view()
    }.also { pendingChanged() }

    /**
     * After a committed change of the pending setting: the same-origin blobs route serves a pending bundled store too
     * ([StoreSettings.bundledRouteBucket]) — its check uploads through it — so the cached resolution is forgotten and
     * the ingress re-rendered, in [check]'s promotion order.
     */
    private fun pendingChanged() {
        settings.invalidate()
        projector.regenerate()
    }

    /**
     * Where the deployment offers the bundled store and no setting is saved — none in use, none pending — save the
     * bundled store as pending (ADR 0089 decision 3); the boot worker then checks it ([BundledStoreAdoption]). A bundled
     * store already pending from an earlier boot is [StoreAdoption.RESUMED], for that worker to check again. Anything
     * the Server Owner saved, in use or pending, is never replaced.
     */
    fun adoptBundled(): StoreAdoption =
        QuarkusTransaction.requiringNew().call { adoptInTransaction() }.also {
            when (it) {
                StoreAdoption.ADOPTED -> {
                    log.info("No store setting; adopting the bundled store and checking it.")
                    pendingChanged()
                }
                StoreAdoption.RESUMED -> log.info("The bundled store is still pending from an earlier start; checking it again.")
                StoreAdoption.NONE -> Unit
            }
        }

    /** [adoptBundled]'s transaction, in the caller's: the condition is read again under the singleton lock, so a save
     *  of the Server Owner's that committed first wins. */
    internal fun adoptInTransaction(): StoreAdoption {
        if (!hint.available) return StoreAdoption.NONE
        lockInstance()
        if (store.find() != null) return StoreAdoption.NONE
        val pending = store.findPending()
        return when {
            pending == null -> {
                store.savePending(StoreSettingRow.BUNDLED)
                StoreAdoption.ADOPTED
            }
            pending.bundled -> StoreAdoption.RESUMED
            else -> StoreAdoption.NONE
        }
    }

    /** Whether the bundled store is the pending setting — what the adoption's check loop goes on while. */
    fun bundledPending(): Boolean = QuarkusTransaction.requiringNew().call { store.findPending()?.bundled == true }

    /**
     * Check the pending setting, or with none pending the setting in use. A pending setting that passes replaces the
     * one in use (decision 5); one that fails stays pending and changes nothing the instance does. With [onlyPending]
     * — the boot's own check of the store it adopted — nothing is checked unless that very setting is pending
     * ([StoreCheckRun.NotConfigured]): a store the Server Owner saved meanwhile is theirs to check.
     */
    fun check(onlyPending: StoreSettingRow? = null): StoreCheckRun {
        val (active, pending) = QuarkusTransaction.requiringNew().call { store.find() to store.findPending() }
        if (onlyPending != null && pending != onlyPending) return StoreCheckRun.NotConfigured
        val target = pending ?: active ?: return StoreCheckRun.NotConfigured
        val resolution = if (pending != null) settings.resolve(pending) else settings.current()
        val (connection, unreadableDetail) = when (resolution) {
            is StoreResolution.Ready -> resolution.connection to null
            is StoreResolution.NotSet -> null to "No store is set."
            is StoreResolution.Unavailable -> null to unavailableDetail(resolution)
        }
        val result = checker.run(connection, unreadableDetail, publicOrigin())
        val promoted = QuarkusTransaction.requiringNew().call { settle(target, checkedPending = pending != null, result) }
        if (promoted) {
            settings.invalidate()
            projector.regenerate()
        }
        // What the check's provisioning did is the store-in-use's state only when it checked that store, or promoted
        // the one it checked (StoreProvisioningRunner.recordCheckProvisioning).
        if (pending == null || promoted) {
            when (result.steps.firstOrNull { it.step == StoreCheckStep.PROVISION }?.outcome) {
                StoreCheckOutcome.PASSED -> provisioning.recordCheckProvisioning(true)
                StoreCheckOutcome.FAILED -> provisioning.recordCheckProvisioning(false)
                else -> Unit
            }
        }
        return StoreCheckRun.Done(result)
    }

    /**
     * Record [result] against [target], the setting the check ran against — or, [checkedPending] and passed, put it in
     * use. Whether it promoted. In the caller's transaction.
     *
     * **Under the instance singleton's row lock.** The check itself holds no lock while it talks to the store, so two
     * checks of one pending setting (the boot's of an adopted bundled store and the Server Owner's) can both read it
     * pending; without the lock both promoted, and the second failed on the first's uncommitted active row. With it the
     * second waits, then finds nothing pending and records nothing (`InstanceStoreSliceTest`'s race).
     */
    internal fun settle(target: StoreSettingRow, checkedPending: Boolean, result: StoreCheckResult): Boolean {
        lockInstance()
        // A setting changed while the check ran has not been checked: record only against the one that was.
        return when {
            !checkedPending -> {
                if (store.find() == target) store.recordCheck(result)
                false
            }
            store.findPending() != target -> false
            result.passed -> {
                store.promotePending(result)
                true
            }
            else -> {
                store.recordPendingCheck(result)
                false
            }
        }
    }

    /** The instance singleton's row lock, which [set], the adoption and the check's promotion take first — the writes
     *  that insert a row of the setting, and so could collide on its key. [discardPending] only deletes. */
    private fun lockInstance() {
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
    }

    private fun unavailableDetail(r: StoreResolution.Unavailable): String =
        if (r.hintMissing) "The bundled store is chosen, but this deployment offers none."
        else "The stored ${r.unreadable.joinToString(" and ") { it.label() }} cannot be opened under the current config-secret key — enter it again."

    private fun StoreSecret.label() = when (this) {
        StoreSecret.SECRET_KEY -> "secret key"
        StoreSecret.GARAGE_ADMIN_TOKEN -> "Garage admin token"
    }

    /** The origin browsers open this instance at, for the CORS preflight; null when the deployment names no domain. */
    private fun publicOrigin(): String? =
        config.tenant().ingress().bootstrapDomain().orElse(null)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "https://$it" }

    private sealed interface Built {
        data class Row(val row: StoreSettingRow) : Built
        data class Refused(val outcome: StoreSetOutcome) : Built
    }

    private fun build(u: StoreUpdate, current: StoreSettingRow?): Built {
        if (u.bundled) {
            if (listOf(u.type, u.endpoint, u.publicEndpoint, u.bucket, u.region, u.accessKey, u.secretKey, u.garageAdminEndpoint, u.garageAdminToken).any { it != null }) {
                return Built.Refused(StoreSetOutcome.Invalid)
            }
            if (!hint.available) return Built.Refused(StoreSetOutcome.NoBundledStore)
            return Built.Row(StoreSettingRow.BUNDLED)
        }
        val type = u.type ?: return Built.Refused(StoreSetOutcome.Invalid)
        val endpoint = u.endpoint.clean()
        val bucket = u.bucket.clean()
        val region = u.region.clean()
        val accessKey = u.accessKey.clean()
        val publicEndpoint = u.publicEndpoint.clean()
        if (endpoint == null || bucket == null || region == null || accessKey == null) return Built.Refused(StoreSetOutcome.Invalid)
        if (!isHttpUrl(endpoint) || (publicEndpoint != null && !isHttpUrl(publicEndpoint))) return Built.Refused(StoreSetOutcome.Invalid)
        if (!BlobStoreStartupCheck.isValidBucketName(bucket)) return Built.Refused(StoreSetOutcome.Invalid)

        // A value the request omits keeps the stored one — but only a stored one of THIS kind of setting: a bundled
        // reference holds no secrets, so there is nothing to keep.
        val keptSecretKey = current?.takeIf { !it.bundled }?.secretKey
        val keptToken = current?.takeIf { !it.bundled && it.type == StoreType.GARAGE }?.garageAdminToken
        val secretKey = sealOrKeep(u.secretKey.clean(), keptSecretKey) ?: return Built.Refused(sealFailure(u.secretKey.clean()))

        var garageEndpoint: String? = null
        var garageToken: SealedValue? = null
        if (type == StoreType.GARAGE) {
            garageEndpoint = u.garageAdminEndpoint.clean()?.takeIf { isHttpUrl(it) } ?: return Built.Refused(StoreSetOutcome.Invalid)
            garageToken = sealOrKeep(u.garageAdminToken.clean(), keptToken) ?: return Built.Refused(sealFailure(u.garageAdminToken.clean()))
        } else if (u.garageAdminEndpoint.clean() != null || u.garageAdminToken.clean() != null) {
            return Built.Refused(StoreSetOutcome.Invalid)
        }
        return Built.Row(StoreSettingRow(false, type, endpoint, publicEndpoint, bucket, region, accessKey, secretKey, garageEndpoint, garageToken))
    }

    /** A new value sealed under the current key with that key's fingerprint, else the stored one, else none (null). */
    private fun sealOrKeep(supplied: String?, kept: SealedValue?): SealedValue? {
        if (supplied == null) return kept
        val fingerprint = keys.currentFingerprint()
        if (!seal.configured || fingerprint == null) return null
        val sealed = seal.seal(supplied)
        return SealedValue(sealed.ciphertext, sealed.nonce, fingerprint)
    }

    /** Why [sealOrKeep] gave nothing: a supplied value that could not be sealed, or a required one that is missing. */
    private fun sealFailure(supplied: String?): StoreSetOutcome =
        if (supplied != null) StoreSetOutcome.SecretUnavailable else StoreSetOutcome.Invalid

    /**
     * Whether the change would move blobs to another store (decision 6): any blob exists and the type, endpoint or
     * bucket differ from the store the setting in use names. With none in use there is no earlier store to differ from.
     */
    private fun blobsStoredUnderAnotherStore(current: StoreSettingRow?, next: StoreSettingRow): Boolean {
        if (current == null || !store.blobsStored()) return false
        return identity(current) != identity(next)
    }

    private fun identity(row: StoreSettingRow): Triple<StoreType?, String?, String?> {
        val bundled = if (row.bundled) hint.connection else null
        return if (bundled != null) Triple(bundled.type, bundled.endpoint, bundled.bucket) else Triple(row.type, row.endpoint, row.bucket)
    }

    private fun view(): StoreView {
        val active = store.find()
        return StoreView(
            bundledAvailable = hint.available,
            blobsStored = store.blobsStored(),
            setting = active?.let { settingView(it) },
            lastCheck = if (active == null) null else store.lastCheck(),
            pending = store.findPending()?.let { StorePendingView(settingView(it), store.lastPendingCheck()) },
        )
    }

    private fun settingView(row: StoreSettingRow): StoreSettingView {
        if (row.bundled) {
            val b = hint.connection
            return StoreSettingView(
                bundled = true, type = StoreType.GARAGE,
                endpoint = b?.endpoint, publicEndpoint = b?.publicEndpoint, bucket = b?.bucket, region = b?.region,
                accessKey = b?.accessKey, garageAdminEndpoint = b?.garageAdminEndpoint,
                secretKeySet = b != null, garageAdminTokenSet = b != null, unreadable = emptyList(),
            )
        }
        return StoreSettingView(
            bundled = false, type = row.type ?: StoreType.S3,
            endpoint = row.endpoint, publicEndpoint = row.publicEndpoint, bucket = row.bucket, region = row.region,
            accessKey = row.accessKey, garageAdminEndpoint = row.garageAdminEndpoint,
            secretKeySet = row.secretKey != null, garageAdminTokenSet = row.garageAdminToken != null,
            unreadable = settings.unreadable(row),
        )
    }

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

    private companion object {
        val log: Logger = Logger.getLogger(StoreHandler::class.java)
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI.create(value)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    }.getOrDefault(false)
}
