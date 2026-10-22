package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.blobstore.AdoptionCheckLoop
import me.raddatz.veyq.core.blobstore.BundledHintProvisioned
import me.raddatz.veyq.core.blobstore.StoreSettingRow
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

/**
 * **Where the deployment brings its Garage, the server sets and checks the bundled store itself** (ADR 0089 decision 3,
 * uc.admin.configure-object-store A8). The boot's provisioning of the hint's Garage for an instance with no store in
 * use ends in [BundledHintProvisioned], on the provisioning worker's thread; this then adopts the bundled store
 * ([StoreHandler.adoptBundled]: as pending, only where nothing is saved) and runs the ordinary check on it through
 * [AdoptionCheckLoop] — so only a passing check puts it in use (ADR 0089 decision 5), and the Server Owner opens the page on the
 * result. A bundled store a previous boot adopted and whose check did not pass is checked again the same way: a restart
 * is what an operator does after fixing DNS or the proxy (owner, 2026-10-22).
 *
 * Nothing marks the adoption done; every boot reads the condition again. The worker, and so this, does not run under
 * `%test`: `BundledStoreAdoptionSliceTest` drives [StoreHandler.adoptBundled], `AdoptionCheckLoopTest` the loop.
 */
@ApplicationScoped
class BundledStoreAdoption(
    private val handler: StoreHandler,
) {
    private val loop = AdoptionCheckLoop()

    fun onBundledHintProvisioned(@Observes event: BundledHintProvisioned) {
        if (handler.adoptBundled() == StoreAdoption.NONE) return
        loop.run(
            stillAdopted = { handler.bundledPending() },
            check = { (handler.check(onlyPending = StoreSettingRow.BUNDLED) as? StoreCheckRun.Done)?.result },
        )
    }
}
