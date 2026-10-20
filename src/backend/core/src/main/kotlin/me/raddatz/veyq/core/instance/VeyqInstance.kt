package me.raddatz.veyq.core.instance

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/** Instance-wide constants that exist from first boot. Exactly one row (id = 1). Holds the public instance salt
 *  (ADR 0003). The config-secret KEK's fingerprint lives on each sealed value instead (ADR 0089 decision 4). */
@Entity
@Table(name = "veyq_instance")
@DynamicUpdate
class VeyqInstance : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = SINGLETON_ID

    @Column(name = "instance_salt", nullable = false)
    lateinit var instanceSalt: String

    companion object : PanacheCompanionBase<VeyqInstance, Int> {
        const val SINGLETON_ID = 1
    }
}
