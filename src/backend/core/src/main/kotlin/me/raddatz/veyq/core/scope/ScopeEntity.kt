package me.raddatz.veyq.core.scope

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * A scope — the unit content is keyed on (en.spaces.personal-scope, cr-0026). Generalises the cr-0010
 * "scope == tenant" shortcut into a first-class row: an opaque id + a `kind` (TENANT root / GROUP / PERSONAL),
 * living within exactly one tenant (ADR 0002). Operator-blind: there is **no key here** — the scope key
 * exists only inside each member's ciphertext `wrappedGrant` on their membership row. The TENANT root scope
 * is keyed `id = tenant_id` (born at activation, cr-0010); PERSONAL/GROUP get fresh ids.
 *
 * A shared, **query-only** entity (ADR 0007 — the `MembershipEntity` precedent): it lives in the kernel so the
 * scope-access gate (`core/membership/ScopeAccess`) can read a scope's tenant and `deletedAt` without a
 * kernel → slice import. Every mutation stays in the spaces feature's stores.
 *
 * `@DynamicUpdate`: an UPDATE writes only the columns its transaction changed. The stores mutate this row by
 * read-modify-write (a rename, an icon, a soft-delete), and a full-row UPDATE would write back the [memberRemovals]
 * it read — undoing a removal counted in between, which would silently un-mark every link that removal exposed
 * (ADR 0075 point 4).
 */
@Entity
@Table(name = "veyq_scope")
@DynamicUpdate
class ScopeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "tenant_id", nullable = false, columnDefinition = "uuid")
    lateinit var tenantId: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    /** A PERSONAL scope's owning account, null on every other kind: the key of `ux_scope_personal_owner
     *  (tenant_id, personal_owner)`, which holds "one personal scope per member and tenant" (ADR 0002) against two
     *  creates under two ids. Set once at birth and never changed. */
    @Column(name = "personal_owner", columnDefinition = "uuid")
    var personalOwner: String? = null

    /** A GROUP scope's name, sealed under its **derived name-key `K_name`** (ciphertext base64, cr-0096/ADR 0027);
     *  null for TENANT/PERSONAL (ADR 0014). The server never opens it (no key); it only relays the ciphertext. */
    @Column(name = "encrypted_name")
    var encryptedName: String? = null

    /** For a GROUP: the content-key epoch the `encryptedName` was sealed under (cr-0096/ADR 0027) — 0 at birth,
     *  bumped only by a rename (a plain rotation leaves the name on its epoch). Members peel the chain to this
     *  epoch's key and derive `K_name` from it; a pending invitee is handed `K_name` directly. */
    @Column(name = "name_epoch", nullable = false)
    var nameEpoch: Int = 0

    /** The scope's icon (uc.spaces.set-space-icon): a padded, constant-length image sealed under its **derived
     *  icon-key `K_icon`** (`HKDF(E_iconEpoch, "veyq/v1/space-icon-key")`, opaque base64) — null for no icon. The
     *  first sealed content on a TENANT row; the server never opens it (no key), it only relays the ciphertext. */
    @Column(name = "sealed_icon")
    var sealedIcon: String? = null

    /** The content-key epoch `sealedIcon` was sealed under (uc.spaces.set-space-icon, ADR 0027's per-field epoch) —
     *  0 until an icon is set, bumped only by an icon-set (a plain rotation leaves it). Members peel the chain to
     *  this epoch's key and derive `K_icon`. On a TENANT it is 0 in practice (no routine re-key, ADR 0025). */
    @Column(name = "icon_epoch", nullable = false)
    var iconEpoch: Int = 0

    /** The member who last wrote [encryptedName] (ADR 0093 § 8, en.platform.field-editor): no foreign key, a member who
     *  left stays named; null exactly while the name is. Only a member writes a scope's name or icon, so there is no
     *  guest column. Written by `ScopesStore` with the field, from the authenticated caller; served to no client. */
    @Column(name = "name_editor_user_id", columnDefinition = "uuid")
    var nameEditorUserId: String? = null

    /** The member who last wrote [sealedIcon], as [nameEditorUserId]; a cleared icon clears it. */
    @Column(name = "icon_editor_user_id", columnDefinition = "uuid")
    var iconEditorUserId: String? = null

    /** The scope's **current content-key epoch** (ADR 0024, cr-0088) — 0 until the first rotation. New content
     *  seals under this epoch; a removal bumps it. */
    @Column(name = "current_epoch", nullable = false)
    var currentEpoch: Int = 0

    /** When a GROUP was **soft-deleted** (uc.spaces.delete-group, cr-0090); null = active. While set, the group is
     *  in a grace period — readable but read-only, and the scheduled purge hard-erases it after the retention. */
    /** How many accepted members this scope has lost (ADR 0075 point 4) — bumped in the transaction of every removal
     *  (`ScopesStore.recordRemoval`); never decremented. A share stamps it at mint, so a share minted before a
     *  removal is one the removed member could have rebuilt from its recovery copy. Operational metadata: it counts
     *  membership deletes the operator performs anyway. */
    @Column(name = "member_removals", nullable = false)
    var memberRemovals: Int = 0

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<ScopeEntity, String> {
        const val TENANT = "TENANT"
        const val GROUP = "GROUP"
        const val PERSONAL = "PERSONAL"

        /**
         * Take the scope row's write lock for the caller's transaction — the one serialisation point a rotation
         * commit, a grant written at a stated epoch (a group add, a member invite, its accept), a grant-delivery completion, every scope-rotation origination and every removal of one
         * scope share (lock order: scope → membership → job; several scope rows in id order). Returns the row as it is
         * under the lock. A query, so the `SELECT … FOR UPDATE` reaches the database even when the transaction already
         * holds the row in its persistence context (the `ScopeAccess` gate loads it, a tenant removal reads it to plan
         * its cascade) — and then a refresh, because a query hands back that loaded copy unchanged: a `currentEpoch`
         * read from it was the one from before the lock, at any isolation (an invite minted at an epoch a rotation had
         * just left, ADR 0078). The query flushes this transaction's pending changes to the row first, so the refresh
         * loses none. A lock, not a mutation: the stores still own every write.
         */
        fun lockRow(id: String): ScopeEntity? =
            find("id", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()?.also { getEntityManager().refresh(it) }
    }
}
