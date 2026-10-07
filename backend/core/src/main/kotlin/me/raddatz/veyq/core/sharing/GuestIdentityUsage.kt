package me.raddatz.veyq.core.sharing

/**
 * Whatever of an application still names a guest identity, asked by `SharingStore.dropDeadIdentities` before it
 * deletes one (inbox #229, ADR 0029 §7). An identity goes only when **no** implementation reports it referenced
 * **and at least one was asked** ([unreferenced]); the answers are combined with "any", so order is irrelevant.
 * With no implementation at all nothing can vouch that no row names the identity, so it is kept — fail closed, as
 * a node kind with no `ShareGuestWrites` refuses the share.
 *
 * **That fail-closed case no longer occurs in a running backend** (cr-files-application P3-S3): core implements it
 * itself for its attachments (`AttachmentGuestIdentityUsage`), so the collection is never empty and "some usage was
 * asked" always holds. An application whose rows name a guest identity but which ships no usage of its own is
 * therefore **not** kept safe by this rule any more: its identity is dropped once core's attachments stop naming it
 * — refused loudly only where its column has a foreign key onto `veyq_share_identity` (Memories' `share_identity_id`
 * has none). Every application whose rows name a guest identity implements this interface; that is a rule for the
 * application, no longer a default of the platform. Today: core (an attachment uploaded by the guest) and the media
 * application (a medium, live or trashed — a trashed one can be restored, and the identity's wrapped key is the only
 * way to open an uploader guest's photo — or a bucket owned by the guest).
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
