package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.generated.api.StatementsApi
import me.raddatz.veyq.core.generated.model.MyModerationStatements
import me.raddatz.veyq.core.generated.model.ObjectionSubmission
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.ratelimit.RateLimit
import me.raddatz.veyq.core.ratelimit.RateLimitKey
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * The affected member's statements and objection (uc.moderation.object-to-a-measure): thin over [Statements]. No role
 * and no key proof — a member is told only what names them; the objection is sealed in their browser.
 */
@Authenticated
class StatementsResource(
    private val statements: Statements,
    private val identity: SecurityIdentity,
) : StatementsApi {

    @RateLimit(key = RateLimitKey.USER) // read by every placeholder a member opens; one indexed read per call
    override fun listMyStatements(): Response = Response.ok(MyModerationStatements(statements.mine(identity.principal.name))).build()

    @RateLimit(key = RateLimitKey.USER) // one objection per measure is the database's; this bounds the attempts and the mails
    override fun objectToStatement(statementId: UUID, objectionSubmission: ObjectionSubmission): Response =
        when (val outcome = statements.object_(identity.principal.name, statementId.toString(), objectionSubmission.epoch, objectionSubmission.sealedObjection)) {
            is ObjectionResult.Received -> Response.status(Response.Status.CREATED).entity(outcome.statement).build()
            is ObjectionResult.Refused -> httpError(outcome)
        }
}
