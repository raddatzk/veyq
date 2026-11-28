package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.api.apis.RekeyApi
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.models.CompromiseRotation
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * One status line above a space's member list (the web's `TenantMembersPanel.vue` and `GroupMembersPanel.vue`): its
 * [word], whether it warns ([warn]; else it informs) and whether it offers *Start again* ([requeue]).
 */
@Serializable
data class SpaceStatusLine(val word: Msg, val warn: Boolean, val requeue: Boolean = false)

/**
 * The status lines of the Members panel and a group's page, the web's rule ported (ADR 0148 § Members, § Groups): a
 * stuck compromise rotation's notice for an owner (uc.spaces.complete-queued-rotation E2/E8 — `failed` offering *Start
 * again*, `stalled` none: its remedy is a co-owner, which the list offers), then either the resealing hint (this device
 * seals the scope-level rows again under a new key, ADR 0115 decision 2) or, for an owner, the re-keying hint — a close's
 * where only closed links' subtrees are due (ADR 0114 decision 9), a rotation's otherwise.
 */
object SpaceStatus {
    /** Why a scope re-keys, from its re-key listing (the web's `rekeyingCause`). */
    enum class Cause { ROTATION, CLOSE }

    /**
     * The lines in the web's order. **Load-bearing:** the stuck notice and the re-keying hint are an owner's alone (the
     * server serves `compromiseRotation` only to an owner; the web shows `rekeying` only to one), and resealing wins over
     * re-keying.
     */
    fun lines(owner: Boolean, stuck: CompromiseRotation?, resealing: Boolean, rekeying: Boolean, cause: Cause?): List<SpaceStatusLine> {
        val lines = ArrayList<SpaceStatusLine>(2)
        if (owner) when (stuck) {
            CompromiseRotation.failed -> lines += SpaceStatusLine(Msg.MEMBERS_COMPROMISE_ROTATION_FAILED, warn = true, requeue = true)
            CompromiseRotation.stalled -> lines += SpaceStatusLine(Msg.MEMBERS_COMPROMISE_ROTATION_STALLED, warn = true)
            else -> Unit
        }
        if (resealing) lines += SpaceStatusLine(Msg.SPACE_LOCK_RESEALING, warn = false)
        else if (owner && rekeying) {
            lines += SpaceStatusLine(if (cause == Cause.CLOSE) Msg.SHARE_CLOSED_REKEYING else Msg.ROTATION_STATUS_HINT_REKEYING, warn = false)
        }
        return lines
    }

    /** A re-key's cause from its listing: a rotation's while a due node carries a key of an epoch before [currentEpoch]. */
    fun cause(due: List<Int>, currentEpoch: Int): Cause? = when {
        due.isEmpty() -> null
        due.any { it < currentEpoch } -> Cause.ROTATION
        else -> Cause.CLOSE
    }

    /**
     * [scopeId]'s lines as read now for [owner] with the stuck state [stuck] (the tenant's from `listMyTenants`, a group's
     * from `listMyScopes`): the row's `rekeying` read anew, its cause from `listRekeyNodes` (unknown reads as a rotation's,
     * as on the web). A read that fails shows no re-keying hint — the lines are display only.
     */
    internal suspend fun read(tenant: TenantSession, scopeId: String, owner: Boolean, stuck: CompromiseRotation?, rekeying: Boolean? = null): List<SpaceStatusLine> {
        val account = tenant.account
        val re = owner && (rekeying ?: guarded(false) {
            val r = account.respond { ScopesApi(tenant.base, account.http).listMyScopes() }
            r.success && r.body().firstOrNull { it.id == scopeId }?.rekeying == true
        })
        val cause = if (!re) null else guarded(Cause.ROTATION) {
            val r = account.respond { RekeyApi(tenant.base, account.http).listRekeyNodes(scopeId) }
            if (!r.success) Cause.ROTATION else r.body().let { l -> cause(l.nodes.filter { it.due }.map { it.scopeEpoch }, l.currentEpoch) ?: Cause.ROTATION }
        }
        return lines(owner, stuck, Resealing.has(scopeId), re, cause)
    }

    /** How *Start again* ended: [done] where the server took it, [stuck] the row's new state, [notice] what is said on a failure. */
    @Serializable
    data class Requeued(val done: Boolean, val stuck: Boolean, val notice: Notice? = null)

    /**
     * *Start again* (uc.spaces.complete-queued-rotation E8, the web's `requeueScopeRotation`): the dead-lettered rotation
     * queued once more, so this device's worker claims it on its next pass. The switcher's and the drawer's marks are read
     * anew ([SpaceFaces]).
     */
    internal suspend fun requeue(tenant: TenantSession, scopeId: String): Requeued = try {
        val r = tenant.account.respond { ScopesApi(tenant.base, tenant.account.http).requeueScopeRotation(scopeId) }
        if (!r.success) Requeued(false, true, Notice(Msg.NODE_SAVE_FAILED))
        else {
            val stuck = r.body().compromiseRotation != null
            runCatching { tenant.faces.load() }.onFailure { if (it is CancellationException) throw it }
            Requeued(true, stuck)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Requeued(false, true, Notice(Msg.NODE_SAVE_FAILED))
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }
}

/**
 * The scopes this process seals the scope-level rows of again (`ScopeRotation.stageScopeLevelRekey`, ADR 0115 decision 2):
 * the web's `resealingScopes`, read by [SpaceStatus.read] for the resealing hint.
 */
object Resealing {
    private val _scopes = MutableStateFlow<Set<String>>(emptySet())

    /** The scopes staging now. */
    val scopes: StateFlow<Set<String>> = _scopes.asStateFlow()

    fun has(scopeId: String): Boolean = scopeId in _scopes.value

    /** [block] with [scopeId] held as resealing, released however it ends. */
    internal suspend fun <T> during(scopeId: String, block: suspend () -> T): T {
        _scopes.update { it + scopeId }
        try {
            return block()
        } finally {
            _scopes.update { it - scopeId }
        }
    }
}
