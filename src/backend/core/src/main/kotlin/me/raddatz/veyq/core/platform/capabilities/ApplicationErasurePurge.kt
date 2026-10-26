package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant

/**
 * The erasure of an application left switched off (uc.platform.erase-switched-off-application, ADR 0079 decision 8,
 * ADR 0088 decision 5). Each tick, in this order:
 *
 *  1. **opens a missing row** — an application off for a tenant with no [ApplicationErasureEntity] (one switched off
 *     before this table existed) gets its window from now, as a fresh switch-off would: never a day earlier than
 *     members can be told;
 *  2. **erases what is due** — for each row whose [ApplicationErasureEntity.dueAt] has come, every scope of the tenant
 *     (its personal spaces, its groups, a group in its grace) in a `requiringNew` transaction of its own: the tenant's
 *     row lock, the row read again under it (still there, still due), the scope's lock, then every node whose kind
 *     [NodeKindRules.applicationOf] names the row's application and that was made before the day, trashed ones
 *     included, handed to [NodeSubtreePurge.eraseNodes] — the trash purge's own erase, so every eraser, the store
 *     reclaim, the erasure ledger's `NODE` lines, the end of every measure and every hold (which the erase never reads)
 *     apply unchanged. Core folders stay (they organise only), and so do scope keys and memberships. A scope that fails
 *     is logged and taken again at the next tick; an erased scope finds nothing the second time;
 *  3. **ends the window** once every scope succeeded: the row goes, or — when the application is still, or again, off
 *     for the tenant — starts again from now, its notice owed again;
 *  4. **mails** the owed notices and the due reminders ([ApplicationErasureMail]).
 *
 * Core names no application: the applications are the register's roots ([ApplicationErasureStore.APPLICATIONS]) and a
 * node's is its kind's own answer. Nothing is decrypted. Every replica runs every tick; the row locks keep that safe.
 * The `now` parameter is the clock a test drives, as `TenantHandler.delete(now = …)`'s.
 */
@ApplicationScoped
class ApplicationErasurePurge(
    private val store: ApplicationErasureStore,
    private val nodes: NodeSubtreePurge,
    private val mail: ApplicationErasureMail,
    // MutableList, not List: ArC rejects kotlin List with @All (see ScopePurge).
    @All private val kindRules: MutableList<NodeKindRules>,
) {
    private val log = Logger.getLogger(ApplicationErasurePurge::class.java)

    /** What one tick did. */
    data class Tick(val opened: Int, val erasedNodes: Int, val ended: Int, val notices: Int, val reminders: Int)

    @Scheduled(every = "{veyq.application.erasure.purge.every}")
    fun scheduled() {
        run(Instant.now())
    }

    fun run(now: Instant): Tick {
        val opened = openMissing(now)
        var erased = 0
        var ended = 0
        dueRows(now).forEach { key ->
            val scopes = QuarkusTransaction.requiringNew().call {
                ScopeEntity.list("tenantId", key.tenantId).map { it.id }.sorted()
            }
            var complete = true
            scopes.forEach { scopeId ->
                try {
                    erased += QuarkusTransaction.requiringNew().call { eraseIn(key, scopeId, now) }
                } catch (e: Exception) {
                    complete = false
                    log.error("the erasure of ${key.application} in scope $scopeId failed; it stays due and the purge continues", e)
                }
            }
            if (!complete) return@forEach
            try {
                if (QuarkusTransaction.requiringNew().call { end(key, now) }) ended++
            } catch (e: Exception) {
                log.error("the end of the erasure of ${key.application} in tenant ${key.tenantId} failed; it is taken again", e)
            }
        }
        return Tick(opened, erased, ended, mail.notices(now), mail.reminders(now))
    }

    private fun dueRows(now: Instant): List<ApplicationErasureKey> = QuarkusTransaction.requiringNew().call {
        ApplicationErasureEntity.list("dueAt <= ?1 order by dueAt, tenantId, application", now).map { it.key() }
    }

    /**
     * Erase, in [scopeId], every node of [key]'s application made before the day. Joins the caller's transaction. The
     * tenant's row lock first ([ApplicationErasureStore]'s order): every writer of the row's existence holds it, so the row
     * read under it is the one the switches left, and a switch-on waits for this scope.
     */
    fun eraseIn(key: ApplicationErasureKey, scopeId: String, now: Instant): Int {
        store.lockTenant(key.tenantId) ?: return 0
        val row = ApplicationErasureEntity.findById(key)?.takeIf { !it.dueAt.isAfter(now) } ?: return 0
        ScopeEntity.lockRow(scopeId) ?: return 0
        val doomed = NodeEntity.find("scopeId = ?1 and createdAt < ?2", scopeId, row.dueAt).list()
            .filter { NodeKindRules.applicationOf(kindRules, it.kind) == key.application }
            .map { it.id }
        return if (doomed.isEmpty()) 0 else nodes.eraseNodes(scopeId, doomed)
    }

    /** Every scope is done: end the row under both locks, or start a fresh window while the application is still off. */
    private fun end(key: ApplicationErasureKey, now: Instant): Boolean {
        store.lockInstance()
        store.lockTenant(key.tenantId) ?: return false
        val row = ApplicationErasureEntity.findById(key)?.takeIf { !it.dueAt.isAfter(now) } ?: return false
        if (store.isOff(key.tenantId, key.application)) {
            row.switchedBy = store.levelOf(key.application).name
            row.offSince = now
            row.dueAt = now.plus(store.window)
            row.noticeMailedAt = null
            row.reminderMailedAt = null
        } else {
            row.delete()
        }
        log.infof("the erasure of %s in tenant %s is complete", key.application, key.tenantId)
        return true
    }

    /** Step 1: a row for every application off for a tenant that has none, each under both locks. */
    private fun openMissing(now: Instant): Int {
        val missing = QuarkusTransaction.requiringNew().call {
            val instanceOff = CapabilitySwitchEntity.listAll().map { it.id }.filter { it in ApplicationErasureStore.APPLICATIONS }
            val tenants = if (instanceOff.isEmpty()) emptyList() else TenantEntity.findAll().list().map { it.id }
            val tenantOff = TenantCapabilitySwitchEntity.list("capabilityId in ?1", ApplicationErasureStore.APPLICATIONS)
                .map { ApplicationErasureKey(it.tenantId, it.capabilityId) }
            val existing = ApplicationErasureEntity.listAll().mapTo(HashSet()) { it.key() }
            (tenants.flatMap { t -> instanceOff.map { ApplicationErasureKey(t, it) } } + tenantOff).toSet() - existing
        }
        return missing.sortedWith(compareBy({ it.tenantId }, { it.application })).count { key ->
            runCatching {
                QuarkusTransaction.requiringNew().call {
                    store.lockInstance()
                    store.lockTenant(key.tenantId) ?: return@call false
                    val had = ApplicationErasureEntity.findById(key) != null
                    !had && store.reconcile(key.tenantId, key.application, store.levelOf(key.application), now) != null
                }
            }.getOrElse { e ->
                log.error("the erasure row of ${key.application} in tenant ${key.tenantId} could not be opened; it is taken again", e)
                false
            }
        }
    }
}
