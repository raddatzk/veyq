package com.veyq.core.admin.instance

import com.veyq.core.generated.model.InstanceStoreProvisioning
import com.veyq.core.blobstore.StoreProvisioningRunner
import com.veyq.core.blobstore.StoreProvisioningState
import jakarta.enterprise.context.ApplicationScoped

/**
 * How far this instance got with bringing its object store up (ADR 0067), for the Server Owner's operational
 * read — the sibling of [ReleaseIdentity], and the same kind of value: server self-description, no user state.
 *
 * **It exists because provisioning deliberately cannot fail the boot.** ADR 0067 Decision 3 runs the bring-up
 * in the background so an unreachable store can never restart-loop the stack the way ADR 0063's projector once
 * did — which means an instance with a broken store *starts, and looks healthy*. Without this read the
 * operator's first notice would be a photo that will not upload. A log line reaches whoever is already reading
 * logs; this reaches whoever is looking at the instance.
 *
 * The mapping is a total function over [StoreProvisioningState] rather than an `if succeeded … else FAILED`:
 * the two states that are not outcomes — a `type: s3` store veyq must never poke, and a run still in flight —
 * are answers the operator acts on differently, and folding either into a failure would be a lie the read
 * cannot be argued out of later.
 */
@ApplicationScoped
class StoreProvisioningReport(
    private val runner: StoreProvisioningRunner,
) {
    fun state(): InstanceStoreProvisioning.State = when (runner.state()) {
        StoreProvisioningState.NOT_ATTEMPTED -> InstanceStoreProvisioning.State.NOT_ATTEMPTED
        StoreProvisioningState.IN_PROGRESS -> InstanceStoreProvisioning.State.IN_PROGRESS
        StoreProvisioningState.PROVISIONED -> InstanceStoreProvisioning.State.PROVISIONED
        StoreProvisioningState.FAILED -> InstanceStoreProvisioning.State.FAILED
    }
}
