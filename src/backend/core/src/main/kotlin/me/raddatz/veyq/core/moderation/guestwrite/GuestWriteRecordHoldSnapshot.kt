package me.raddatz.veyq.core.moderation.guestwrite

import me.raddatz.veyq.core.moderation.hold.HoldSnapshot
import me.raddatz.veyq.core.moderation.hold.HoldSnapshotContributor
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * The guest-write records a hold copies (ADR 0093 § 4: "attribution (§ 8) and guest-write records (§ 10)"): every
 * record whose object is a row the hold already copied — a node, a medium, a file — or a guest identity a copied row
 * names; for a reported link, also every record its guests wrote. Phase 900, after every other contributor, so the
 * objects are known. The copy is a hold row like any other, so it outlives N and the switch: it ends with the hold.
 */
@ApplicationScoped
class GuestWriteRecordHoldSnapshot : HoldSnapshotContributor {
    override val phase = 900

    override fun contribute(target: HoldTarget, snapshot: HoldSnapshot) {
        val ids = LinkedHashSet<String>()
        if (target is HoldTarget.Name) {
            // A name: the records of that name's writes alone — the node's other writes were not reported — and of the
            // identity that last wrote it.
            GuestWriteRecordEntity.list("objectId = ?1 and fieldGroup = ?2", target.nodeId, GuestWriteRecords.NAME).mapTo(ids) { it.id }
            snapshot.identityIds.filter(UUID_SHAPE::matches).chunked(BATCH).forEach { batch ->
                GuestWriteRecordEntity.list("objectId in ?1", batch).mapTo(ids) { it.id }
            }
            snapshot.copy("veyq_guest_write_record", "id", ids)
            return
        }
        // Only a UUID can be an object: a copied share row's id is no UUID, and a native UUID column refuses one outright.
        (snapshot.copiedIds + snapshot.identityIds).filter(UUID_SHAPE::matches).chunked(BATCH).forEach { batch ->
            GuestWriteRecordEntity.list("objectId in ?1", batch).mapTo(ids) { it.id }
        }
        if (target is HoldTarget.Link) {
            ShareIdentityEntity.list("shareId", target.shareId).map { it.id }.chunked(BATCH).forEach { batch ->
                GuestWriteRecordEntity.list("shareIdentityId in ?1", batch).mapTo(ids) { it.id }
            }
        }
        snapshot.copy("veyq_guest_write_record", "id", ids)
    }

    private companion object {
        const val BATCH = 500
        val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
