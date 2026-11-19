package me.raddatz.veyq.mobile.session.rotation

import kotlinx.coroutines.CancellationException
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.api.apis.AccountRotationApi
import me.raddatz.veyq.mobile.api.apis.ScopesApi
import me.raddatz.veyq.mobile.api.models.RotationPlanScope
import me.raddatz.veyq.mobile.api.models.RotationScopeState
import me.raddatz.veyq.mobile.api.models.ScopeKind
import me.raddatz.veyq.mobile.api.models.ScopeSummary
import me.raddatz.veyq.mobile.session.GrantOutcome
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.VeyqAccount

// uc.account.rotate-keys, the half after the publish — a port of `src/web/src/features/account/rotation/finishRotation.ts`
// (ADR 0111 decision 8, ADR 0148 decision 7). The publish granted every scope the account holds a grant on, at its CURRENT
// epoch, to the new identity, and wrote a pending re-key row per scope; what is left is to close the old identity out of
// what comes next: the account mints each such scope's next epoch itself, from any device of it that unlocks. No old key
// is needed: the account's (published) identity opens the self-grant the publish wrote, whatever tenant the scope is in.

/**
 * Commits the next epoch of every scope this account's last rotation left `waiting`, then answers the states as they read
 * after (`waiting`, `rekeying`, `secured`, `blocked`; empty for an account that never rotated). One pass at a time per
 * account: a second asker waits for the running one and gets its answer. Runs by itself at every unlock and every 45 s
 * while the account is open and online ([RotationFinisher]); a caller may also ask (the rotating device right after its
 * publish). Throws where the states do not read; a closed account finishes nothing and answers no states.
 */
@Throws(Exception::class)
suspend fun VeyqAccount.finishRotation(): List<RotationScopeState> = rotations.finish()

/**
 * One pass (the web's `finishOnce`): the states; the waiting scopes; the plan; per waiting scope its members read now and
 * the epoch committed from the plan row's self-grant ([planOpener]), the scope's tenant judged through the plan's TENANT row
 * read afresh at each open. A `409 stale_epoch` (after the rebuild) or [EpochMovedOn] is someone else's commit — the row's
 * purpose is met; any other failure leaves the scope waiting for the next pass. A committed scope's nodes are re-keyed
 * best effort (ADR 0109; any member finishes it). The states read again, or the first read where that fails.
 */
internal suspend fun finishOnce(account: VeyqAccount): List<RotationScopeState> {
    if (account.isClosed) return emptyList()
    val api = AccountRotationApi(account.server, account.http)
    val states = account.call { api.listRotationScopeStates() }
    val waiting = states.filter { it.state == RotationScopeState.State.waiting }.map { it.scopeId }
    if (waiting.isEmpty()) return states
    val plan = account.call { api.getAccountRotationPlan() }
    suspend fun reread(scopeId: String) = account.call { api.getAccountRotationPlan() }.firstOrNull { it.scopeId == scopeId }
    val rotation = ScopeRotation(account, AppKinds.scopeRekeyParticipants, AppKinds::tokenInput)
    val committed = ArrayList<RotationPlanScope>()
    for (scopeId in waiting) {
        val row = plan.firstOrNull { it.scopeId == scopeId } ?: continue
        if (row.blocked != null) continue
        try {
            val members = sent(account) { ScopesApi(account.originOfTenant(row.tenantId), account.http).listScopeMembers(scopeId) }.body()
            // The tenant's row read afresh at each open, never the pass's first read: the TENANT scope is usually committed
            // earlier in this pass, which raises this device's floor past the self-grant that row carries.
            val openTenant: RotationOpener = { tenantId ->
                val tenantRow = reread(tenantId)
                if (tenantRow == null || tenantRow.blocked != null) null else planOpener(account, tenantRow) { tenantRow }(tenantId)
            }
            rotation.commitRotation(scopeId, members, planOpener(account, row) { reread(scopeId) }, openTenant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val metByAnother = e is EpochMovedOn || (e is RotationRefused && e.status == 409 && e.code == "stale_epoch")
            if (!metByAnother) continue // stays waiting; the next pass retries
        }
        committed += row
    }
    rekeyNodes(account, committed)
    return try {
        account.call { api.listRotationScopeStates() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        states
    }
}

/**
 * The web's `planOpener`: the caller's grant from a row of the rotation's plan, opened with the account's identity as a
 * tenant's opening opens it (verified, never below this device's floor) but even where it lags — a rotation states the
 * epoch it creates. The first open uses [row]; a rebuild re-reads the plan, and a scope whose epoch moved past [row]'s
 * throws [EpochMovedOn]. Null where the row is gone or its grant stays closed.
 */
internal fun planOpener(account: VeyqAccount, row: RotationPlanScope, reread: suspend () -> RotationPlanScope?): RotationOpener {
    var first = true
    return { scopeId ->
        val now = if (first) row else reread()
        first = false
        when {
            now == null || now.scopeId != scopeId -> null
            now.currentEpoch != row.currentEpoch -> throw EpochMovedOn(scopeId)
            else -> when (val o = account.openOwnGrant(summaryOf(now), null, lagging = true)) {
                is GrantOutcome.Opened -> OpenedScope(now.scopeId, now.tenantId, now.kind.value, now.grantedEpoch.toLong(), o.key)
                is GrantOutcome.Refused -> null
            }
        }
    }
}

/** A plan row as the grant check reads a scope row: the same grant fields, no name and no role (`rowOfPlan`). */
internal fun summaryOf(row: RotationPlanScope) = ScopeSummary(
    id = row.scopeId, kind = ScopeKind.decode(row.kind.value) ?: ScopeKind.unknown_default_open_api, role = ScopeSummary.Role.MEMBER,
    tenantId = row.tenantId, nameEpoch = 0, iconEpoch = 0, currentEpoch = row.currentEpoch, grantedEpoch = row.grantedEpoch,
    wrappedGrant = row.wrappedGrant, granterUserId = row.granterUserId, grantSignature = row.grantSignature,
    granterIdentityPublicKey = row.granterIdentityPublicKey,
)

/**
 * Each committed scope's nodes re-keyed, best effort (the web's `rekeyScope` after each commit): per tenant one session
 * opened after the pass's commits, so it holds the new keys. A tenant this account does not list, or a run that fails,
 * leaves the marks for the next run or another member.
 */
private suspend fun rekeyNodes(account: VeyqAccount, committed: List<RotationPlanScope>) {
    for ((tenantId, rows) in committed.groupBy { it.tenantId }) {
        val ref = account.tenants.firstOrNull { it.tenantId == tenantId } ?: continue
        val session = try {
            account.open(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            continue
        }
        try {
            for (row in rows) {
                try {
                    NodeRekey.rekeyScope(session, row.scopeId, AppKinds.rekeys, AppKinds::tokenInput) { NodeCreate.uuid(account) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // best effort
                }
            }
        } finally {
            session.close()
        }
    }
}
