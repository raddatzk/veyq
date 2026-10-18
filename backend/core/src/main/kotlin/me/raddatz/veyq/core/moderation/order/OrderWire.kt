package me.raddatz.veyq.core.moderation.order

import me.raddatz.veyq.core.generated.model.AuthorityOrderRequest
import me.raddatz.veyq.core.http.httpError
import jakarta.ws.rs.core.Response

/**
 * The two doors' shared wire half (ADR 0099 § 6: "two operations on one service" — the moderator's `enterModerationOrder`
 * and the Server Owner's `enterInstanceModerationOrder`): the request as the handler takes it, and its answer.
 */
fun AuthorityOrderRequest.toOrderIn(): OrderIn = OrderIn(
    id = id.toString(),
    kind = OrderKind.valueOf(kind.name),
    shareId = shareId,
    receivedAt = receivedAt.toInstant(),
    epoch = reference.epoch,
    sealedReference = reference.sealedReference,
    holdDays = holdDays,
    deferStatementUntil = deferStatementUntil?.toInstant(),
)

/** An order's answer: `201` with the order as recorded, or its refusal. */
fun ordered(outcome: OrderOutcome): Response = when (outcome) {
    is OrderOutcome.Entered -> Response.status(Response.Status.CREATED).entity(outcome.order).build()
    is OrderOutcome.Refused -> httpError(outcome)
}
