package me.raddatz.veyq.core.account.kitrecord

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/**
 * The account's kit record (ADR 0157 decision 3, uc.account.renew-emergency-kit): one blob sealed under the Master Key
 * (`seal` purpose `kit-record`) holding the layout of the last Emergency Kit the account made. The server stores and
 * serves it verbatim and never opens it — it learns no layout number, only that the account wrote a small document at a
 * time. One row per account, keyed by `accountId`; no row until a flow that makes a kit wrote one (an account whose kit
 * predates the record reads as `{ blob: null }`, layout 1 to the client).
 *
 * Unlike the device register it carries **no revision**: last writer wins (the contract's `x-veyq-concurrency` says
 * why). Every writer replaces the row whole ([KitRecordStore.replace]).
 */
@Entity
@Table(name = "veyq_kit_record")
@DynamicUpdate
class KitRecordEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", columnDefinition = "uuid")
    lateinit var accountId: String

    @Column(name = "sealed_blob", nullable = false)
    lateinit var sealedBlob: String

    companion object : PanacheCompanionBase<KitRecordEntity, String>
}
