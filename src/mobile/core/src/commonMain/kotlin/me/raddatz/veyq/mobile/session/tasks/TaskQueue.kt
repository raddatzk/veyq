package me.raddatz.veyq.mobile.session.tasks

import io.ktor.client.HttpClient
import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.apis.TasksApi
import me.raddatz.veyq.mobile.api.models.ClaimRequest
import me.raddatz.veyq.mobile.api.models.ClaimedJob
import me.raddatz.veyq.mobile.api.models.CompleteRequest
import me.raddatz.veyq.mobile.api.models.DeclineRequest
import me.raddatz.veyq.mobile.api.models.FailRequest
import me.raddatz.veyq.mobile.api.models.GrantDeliveryTarget
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.session.Anchor
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.ServedIdentity
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.TenantView
import me.raddatz.veyq.mobile.session.TrustStateRefused
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.rotation.EpochMovedOn
import me.raddatz.veyq.mobile.session.rotation.OpenedScope
import me.raddatz.veyq.mobile.session.rotation.RotationRefused
import me.raddatz.veyq.mobile.session.rotation.ScopeRotation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The capability tags of the task queue's registry (`contracts/tasks/tags/v1.json`, ADR 0021): each the first 4 bytes of
 * SHA-256 over `veyq/v1/task-capability/<name>`, lowercase hex. Held to the registry by `TaskTagsTest`.
 */
internal object CapabilityTags {
    const val GRANT_DELIVERY = "02f271ff"
    const val SCOPE_ROTATION = "663157d8"
}

/** A refusal of a task call: its status and the error code the server named. */
internal class TaskRefused(val status: Int, val code: String?) : Exception("HTTP $status${code?.let { " $it" } ?: ""}")

/** The task queue's calls (`contracts/api/tasks.openapi.yaml`), the seam [TaskPass] is tested over. */
internal interface TaskWire {
    /** One job leased for [tags] on [scopeId]; null when nothing is claimable (`204`). */
    suspend fun claim(tags: List<String>, scopeId: String): ClaimedJob?
    suspend fun heartbeat(jobId: String)
    /** Throws [TaskRefused] for any answer but `204`. */
    suspend fun complete(jobId: String, request: CompleteRequest)
    suspend fun fail(jobId: String, reason: String)
    suspend fun decline(jobId: String, reason: String)
}

/** What a pass needs from the open tenant, the seam [TaskPass] is tested over ([SessionWork] is the real one). */
internal interface TaskWork {
    /** The open shared scopes (TENANT and GROUP: a PERSONAL scope has no one to deliver to), each with whether this account owns it. */
    fun sharedScopes(): List<Pair<String, Boolean>>
    /** A copy of the key this device holds [scopeId] at, its epoch and the scope's kind; null where it is not open. */
    fun keyOf(scopeId: String): HeldKey?
    /** The anchor gate (ADR 0028 decision 2) for a delivery target, judged in the scope's tenant; throws [TrustStateRefused]. */
    suspend fun anchor(target: GrantDeliveryTarget): Anchor
    /** [key] sealed to the target and the grant statement signed (the web's `sealGrant`). */
    fun seal(key: HeldKey, scopeId: String, target: GrantDeliveryTarget): VeyqAccount.SelfGrant
    /** The scope list read anew; whether [scopeId] is served as re-keying. */
    suspend fun rekeying(scopeId: String): Boolean
    /** Every due node of [scopeId] this device re-keys; true once none is due. */
    suspend fun rekey(scopeId: String): Boolean
    /** [scopeId]'s directory read NOW — after the claim — and its next epoch committed over it (`commitRotation`). */
    suspend fun rotate(scopeId: String)
}

internal class HeldKey(val key: ByteArray, val epoch: Int, val kind: String)

/** What one pass did: grants delivered, rotations completed. */
@PlatformApi
@HiddenFromObjC
data class TaskOutcome(val delivered: Int, val rotated: Int)

/**
 * One pass over the task queue's jobs (ADR 0021, background-tasks.md), the port of the web's `runGrantDeliveryWorker` and
 * `runScopeRotationWorker`: for each open shared scope, claim with the scope named — `grant-delivery`, and `scope-rotation`
 * where this account owns the scope — until nothing is claimable or a refusal says this worker is behind.
 *
 * - **grant-delivery**: the target anchored in the scope's tenant ([TaskWork.anchor]); an `anomaly` is declined
 *   (`identity_unanchored`), a refused pin store pauses the scope (nothing declined); else the scope key is sealed to the
 *   target and the completion states the epoch. `409 stale_epoch` / `recipient_key_not_current` stops the scope.
 * - **scope-rotation**: a scope already re-keying is re-keyed and completed (an earlier lease committed it); otherwise —
 *   and where that completion is `rotation_not_committed` — the directory is read after the claim and the next epoch
 *   committed, the nodes re-keyed, the job completed. A re-key that does not finish keeps the lease. `stale_epoch` and
 *   `not_scope_owner` are this worker's and are declined; anything else fails the job (`rotate-failed`). The lease is
 *   renewed every [renew] while a rotation runs.
 * - a job of another tag is failed back (`not-grant-delivery` / `not-scope-rotation`).
 */
internal class TaskPass(private val wire: TaskWire, private val work: TaskWork, private val renew: Duration = RENEW) {

    suspend fun run(): TaskOutcome {
        var delivered = 0
        var rotated = 0
        for ((scopeId, owner) in work.sharedScopes()) {
            val tags = if (owner) listOf(CapabilityTags.GRANT_DELIVERY, CapabilityTags.SCOPE_ROTATION) else listOf(CapabilityTags.GRANT_DELIVERY)
            drain@ while (true) {
                val job = wire.claim(tags, scopeId) ?: break
                when {
                    job.capabilityTag == CapabilityTags.GRANT_DELIVERY && job.grantDelivery != null ->
                        when (deliver(job, job.grantDelivery)) {
                            Delivery.DELIVERED -> delivered++
                            Delivery.NEXT -> {}
                            Delivery.STOP -> break@drain
                        }
                    job.capabilityTag == CapabilityTags.SCOPE_ROTATION && owner -> if (rotate(job)) rotated++
                    else -> wire.fail(job.jobId, if (job.capabilityTag == CapabilityTags.SCOPE_ROTATION) "not-scope-rotation" else "not-grant-delivery")
                }
            }
        }
        return TaskOutcome(delivered, rotated)
    }

    private enum class Delivery { DELIVERED, NEXT, STOP }

    private suspend fun deliver(job: ClaimedJob, target: GrantDeliveryTarget): Delivery {
        val anchor = try {
            work.anchor(target)
        } catch (e: TrustStateRefused) {
            // the pin store itself refused: nothing can be judged — pause, never decline (the lease lapses, the job returns)
            return Delivery.STOP
        }
        if (anchor == Anchor.ANOMALY) {
            wire.decline(job.jobId, "identity_unanchored")
            return Delivery.NEXT
        }
        val held = work.keyOf(job.scopeId) ?: run {
            wire.fail(job.jobId, "not-grant-delivery")
            return Delivery.NEXT
        }
        try {
            val g = work.seal(held, job.scopeId, target)
            wire.complete(job.jobId, CompleteRequest(g.wrappedGrant, held.epoch, g.grantSignature, g.recipientIdentityDigest))
        } catch (e: TaskRefused) {
            // our key is not the scope's current epoch, or the target replaced their keys since the claim: nothing written,
            // the job released — draining on would only meet the same answer
            if (e.status == 409 && (e.code == "stale_epoch" || e.code == "recipient_key_not_current")) return Delivery.STOP
            throw e
        } finally {
            held.key.fill(0)
        }
        return Delivery.DELIVERED
    }

    /** True where the job completed. */
    private suspend fun rotate(job: ClaimedJob): Boolean = coroutineScope {
        val renewing = launch {
            while (isActive) {
                delay(renew)
                try {
                    wire.heartbeat(job.jobId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // a lost lease changes nothing here: the guarded commit and completion decide
                }
            }
        }
        try {
            if (work.rekeying(job.scopeId)) {
                if (!work.rekey(job.scopeId)) return@coroutineScope false
                if (completeOrUncommitted(job.jobId)) return@coroutineScope true
            }
            work.rotate(job.scopeId)
            if (!work.rekey(job.scopeId)) return@coroutineScope false
            wire.complete(job.jobId, CompleteRequest())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val refusal = workerRefusal(e)
            if (refusal != null) wire.decline(job.jobId, refusal) else wire.fail(job.jobId, "rotate-failed")
            false
        } finally {
            renewing.cancel()
        }
    }

    private suspend fun completeOrUncommitted(jobId: String): Boolean = try {
        wire.complete(jobId, CompleteRequest())
        true
    } catch (e: TaskRefused) {
        if (e.status == 409 && e.code == "rotation_not_committed") false else throw e
    }

    companion object {
        /** The web's `LEASE_RENEW_MS`: four renewals inside the server's two-minute lease. */
        val RENEW: Duration = 30.seconds

        /** The decline reason where a rotation was refused for a reason about this worker rather than the job, else null. */
        fun workerRefusal(e: Exception): String? = when {
            e is EpochMovedOn -> "stale_epoch"
            e is RotationRefused && e.status == 409 && e.code == "stale_epoch" -> "stale_epoch"
            e is RotationRefused && e.status == 403 && e.code == "not_scope_owner" -> "not_scope_owner"
            else -> null
        }
    }
}

/** The task queue's calls over the tenant's host, through the account's refresh-on-401. */
internal class HttpTaskWire(private val account: VeyqAccount, private val base: String, private val http: HttpClient) : TaskWire {
    private fun api() = TasksApi(base, http)

    override suspend fun claim(tags: List<String>, scopeId: String): ClaimedJob? {
        val r = account.respond { api().claimTask(ClaimRequest(tags, scopeId)) }
        if (r.status == 204) return null
        if (!r.success) throw TaskRefused(r.status, account.errorCode(r))
        return r.body()
    }

    override suspend fun heartbeat(jobId: String) {
        account.respond { api().heartbeatTask(jobId) }
    }

    override suspend fun complete(jobId: String, request: CompleteRequest) {
        val r = account.respond { api().completeTask(jobId, request) }
        if (!r.success) throw TaskRefused(r.status, account.errorCode(r))
    }

    override suspend fun fail(jobId: String, reason: String) {
        account.respond { api().failTask(jobId, FailRequest(reason)) }
    }

    override suspend fun decline(jobId: String, reason: String) {
        account.respond { api().declineTask(jobId, DeclineRequest(reason)) }
    }
}

/** [TaskWork] over an open tenant: its scopes, its keys, its anchoring, its rotation and re-key. */
internal class SessionWork(private val tenant: TenantSession) : TaskWork {
    private val account = tenant.account
    private val tenantId = tenant.tenant.tenantId

    /** The tenant's members as read for this pass, once and only when a delivery needs them (ADR 0111 decision 15). */
    private var view: TenantView? = null

    override fun sharedScopes(): List<Pair<String, Boolean>> =
        tenant.openScopeIds().filter { tenant.scopeKind(it) != "PERSONAL" }.map { it to tenant.isScopeOwner(it) }

    override fun keyOf(scopeId: String): HeldKey? {
        val (key, epoch) = tenant.scopeKey(scopeId) ?: return null
        val kind = tenant.scopeKind(scopeId) ?: return null
        return HeldKey(key.copyOf(), epoch, kind)
    }

    override suspend fun anchor(target: GrantDeliveryTarget): Anchor {
        val v = view ?: run {
            val (key, epoch) = tenant.scopeKey(tenantId) ?: throw IllegalStateException("the tenant's key is not held")
            val members = readMembers(tenantId)
            TenantView.of(tenantId, members, key, epoch.toLong(), account.suite, account.anchoringOfAccount).also { view = it }
        }
        return account.anchoringOfAccount.anchorIdentity(target.targetUserId, ServedIdentity(target.identityPublicKey, target.keyChainHead), v)
    }

    override fun seal(key: HeldKey, scopeId: String, target: GrantDeliveryTarget): VeyqAccount.SelfGrant =
        account.sealGrant(key.key, scopeId, key.kind, key.epoch.toLong(), target.targetUserId, target.identityPublicKey)

    override suspend fun rekeying(scopeId: String): Boolean {
        tenant.reopenMoved()
        return scopeId in tenant.rekeyingScopes
    }

    override suspend fun rekey(scopeId: String): Boolean {
        val rules = tenant.rekeyRules ?: return false
        val ran = NodeRekey.rekeyScope(tenant, scopeId, rules.kinds, rules.tokenInput) { NodeCreate.uuid(account) }
        if (ran.committed > 0) tenant.noteRekeyed()
        return ran.outcome == NodeRekey.Outcome.DONE
    }

    override suspend fun rotate(scopeId: String) {
        // LOAD-BEARING ORDER (the web's): this read comes after the claim — every event the job carries committed before it
        val members = readMembers(scopeId)
        val opener: suspend (String) -> OpenedScope? = { id ->
            tenant.scopeKey(id)?.let { (k, e) -> tenant.scopeKind(id)?.let { kind -> OpenedScope(id, tenantId, kind, e.toLong(), k.copyOf()) } }
        }
        ScopeRotation(account, AppKinds.scopeRekeyParticipants, AppKinds::tokenInput).commitRotation(scopeId, members, opener, opener)
        // the new epoch's grant opened here, so the re-key and every write after it seal under it
        tenant.reopenMoved()
        tenant.noteRekeyed()
    }

    /** [scopeId]'s members read from the server now — never the kept list. */
    private suspend fun readMembers(scopeId: String): List<ScopeMember> {
        val r = account.respond { ScopesApi(tenant.base, account.http).listScopeMembers(scopeId) }
        if (!r.success) throw RotationRefused(r.status, account.errorCode(r))
        return r.body()
    }
}

/**
 * When the task queue's jobs run on this device (inbox #966): a pass when the tenant is shown ([start]) and every [period]
 * after while it stays open and the account [works] — the web's 45-second poll —, stopped when the tenant closes ([stop]).
 * One pass at a time; a failed pass is silent, the next one retries (the server's attempts dead-letter a job that keeps
 * failing). Never in an OS-scheduled background run: a job is the open tenant's work, as on the web a job is the open tab's.
 */
@PlatformApi
@HiddenFromObjC
class TaskQueue internal constructor(
    private val works: () -> Boolean,
    private val period: Duration = EVERY,
    context: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
    private val pass: suspend () -> TaskOutcome,
) {
    /** [context]'s dispatcher, under a supervisor of its own — a child of [context]'s job where it carries one (a test's). */
    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))
    private val lock = Mutex()
    private var running: Deferred<TaskOutcome>? = null
    private var loop: Job? = null

    /** The running pass's answer, or a new pass's. Throws what the pass threw. */
    suspend fun drain(): TaskOutcome {
        val pending = lock.withLock { running?.takeIf { it.isActive } ?: scope.async { pass() }.also { running = it } }
        return pending.await()
    }

    /** The loop: a pass now, then one every [period], each only while [works]. Idempotent. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (works()) {
                    try {
                        drain()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // silent: the next pass retries
                    }
                }
                delay(period)
            }
        }
    }

    /** Whether the loop runs. */
    val looping: Boolean get() = loop?.isActive == true

    /** The loop and any running pass cancelled; a later [start] starts it anew. */
    fun stop() {
        loop?.cancel()
        loop = null
        running?.cancel()
        running = null
    }

    companion object {
        /** The web's worker period (`createVisibilityPoller(45_000)`). */
        val EVERY: Duration = 45.seconds

        internal fun of(tenant: TenantSession): TaskQueue {
            val wire = HttpTaskWire(tenant.account, tenant.base, tenant.account.http)
            return TaskQueue({ tenant.account.works }) { TaskPass(wire, SessionWork(tenant)).run() }
        }
    }
}
