package com.veyq.app.features.tasks

import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * The scheduled backstop for grant-delivery **origination** (ADR 0021, cr-0076): the server structurally
 * originates a grant-delivery job for every grant-less membership that lacks a live one. The primary path is
 * reconcile-on-claim (a key-holder pulling its scopes materialises the jobs); this backstop covers scopes no
 * key-holder has pulled recently, so a pending member is not stranded if delivery relied solely on a claim.
 * Idempotent — [TasksStore.reconcileGrantDelivery] skips memberships that already have a PENDING/LEASED job.
 */
@ApplicationScoped
class GrantDeliveryOriginator(
    private val store: TasksStore,
    config: VeyqConfig,
) {
    private val maxAttempts: Int = config.tasks().maxAttempts()

    @Transactional
    @Scheduled(every = "{veyq.tasks.grant-delivery.reconcile.every}")
    fun run() {
        store.reconcileAllGrantDelivery(maxAttempts)
    }
}
