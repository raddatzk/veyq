package me.raddatz.veyq.core.identity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * A member's identity **within one tenant** (cr-0083, ADR 0023): a per-`(user, tenant)` row — one per person
 * per tenant, independent of how many scopes they hold there. `username` is a plaintext handle, unique within
 * the tenant (`UNIQUE(tenant_id, username)`); `sealedName` is the first+last name sealed under `K_tenant`
 * (ciphertext, operator-blind) — **nullable**, since a grant-less member seals it only once they hold the key.
 */
@Entity
@Table(name = "veyq_tenant_profile")
@DynamicUpdate
class TenantProfileEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, length = 36)
    lateinit var userId: String

    @Column(name = "tenant_id", nullable = false, length = 36)
    lateinit var tenantId: String

    @Column(name = "username", nullable = false, length = 32)
    lateinit var username: String

    @Column(name = "sealed_name")
    var sealedName: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<TenantProfileEntity, String>
}
