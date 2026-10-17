package me.raddatz.veyq.core.moderation.guestwrite

import me.raddatz.veyq.core.generated.api.InstanceGuestWriteRecordApi
import me.raddatz.veyq.core.generated.model.InstanceGuestWriteRecord
import me.raddatz.veyq.core.generated.model.InstanceGuestWriteRecordUpdate
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.ratelimit.RateLimitExempt
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response

/**
 * HTTP adapter for the guest-write record's retention (ADR 0093 § 10, ADR 0089): the Server Owner reads and sets N.
 * Implements the generated [InstanceGuestWriteRecordApi]; thin (ADR 0001). Admin-channel marked in the contract and
 * gated here by the class-level `@RolesAllowed(SERVER_OWNER)`: a missing token 401, a non-owner 403. The range (1 to
 * 30) is the contract's, refused with 400 before this runs.
 */
@RolesAllowed(SERVER_OWNER)
@RateLimitExempt(
    reason = "Server-Owner-only read and write of one instance-wide value, the record's retention in days. There is no " +
        "per-caller state to enumerate and no work an anonymous caller could amplify; the role gate bounds the caller " +
        "set to one account.",
)
class InstanceGuestWriteRecordResource(private val records: GuestWriteRecords) : InstanceGuestWriteRecordApi {

    override fun getInstanceGuestWriteRecord(): Response = Response.ok(records.retention().toWire()).build()

    override fun setInstanceGuestWriteRecord(instanceGuestWriteRecordUpdate: InstanceGuestWriteRecordUpdate): Response =
        Response.ok(records.setRetention(instanceGuestWriteRecordUpdate.retentionDays).toWire()).build()

    private fun GuestWriteRetention.toWire() = InstanceGuestWriteRecord(retentionDays = days, chosen = chosen)
}
