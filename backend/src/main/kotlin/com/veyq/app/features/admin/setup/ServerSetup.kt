package com.veyq.app.features.admin.setup

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/** The single-owner setup lock. Exactly one row (id = 1) exists once setup is complete. */
@Entity
@Table(name = "veyq_server_setup")
@DynamicUpdate
class ServerSetup : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = SINGLETON_ID

    @Column(name = "completed_at", nullable = false)
    lateinit var completedAt: Instant

    companion object : PanacheCompanionBase<ServerSetup, Int> {
        const val SINGLETON_ID = 1
    }
}
