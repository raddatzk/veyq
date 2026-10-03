package com.veyq.app.core.sharing

/**
 * Whatever of an application still names a guest identity, asked by `SharingStore.dropDeadIdentities` before it
 * deletes one (inbox #229, ADR 0029 §7). An identity goes only when **no** implementation reports it referenced
 * **and at least one was asked** ([unreferenced]); the answers are combined with "any", so order is irrelevant.
 * With no implementation at all nothing can vouch that no row names the identity, so it is kept — fail closed, as
 * a node kind with no `ShareGuestWrites` refuses the share. Today the media application implements it: a
 * medium (live or trashed — a trashed one can be restored, and the identity's wrapped key is the only way to open
 * an uploader guest's photo) or a bucket owned by the guest.
 *
 * Reads the state of the caller's transaction: it must be asked only after the rows that named the identity were
 * deleted (`NodeSubtreeEraser` implementations run first; `NodeSubtreeEraserOrderSliceTest` pins that).
 */
interface GuestIdentityUsage {
    fun stillReferenced(identityId: String): Boolean

    companion object {
        /** Whether [identityId] may be deleted: some usage was asked and none still references it. An empty
         *  [usages] answers false — the identity is kept, never dropped for want of anyone to ask. */
        fun unreferenced(usages: Iterable<GuestIdentityUsage>, identityId: String): Boolean {
            var asked = false
            for (usage in usages) {
                if (usage.stillReferenced(identityId)) return false
                asked = true
            }
            return asked
        }
    }
}
