package me.raddatz.veyq.core.moderation.order

import me.raddatz.veyq.core.content.NodeCover
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.orderBody
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_REFERENCE
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Two removal orders naming one share at once (uc.moderation.authority-order; ADR 0093 § 12), run on MariaDB by
 * `scripts/run_backend_races_on_mariadb.py`. Both take the share's space row first (`AuthorityOrderHandler.enterIn`, as a
 * report does), so the second waits for the first's commit, then finds its blocks in force and keeps them: **one block per
 * kind and target, and the second order answers with the first's block time** — never a duplicate measure, never a
 * refusal of an authority's order. Forced ([whileUncommitted]): the first order's writes held uncommitted, the second sent
 * inside that window.
 */
@QuarkusTest
class AuthorityOrderRacesSliceTest {

    @Inject
    lateinit var orders: AuthorityOrderHandler

    @Test
    fun aSecondRemovalOrderInsideTheFirstKeepsItsBlocksAndAnswersItsBlockTime() {
        val mod = CaseTestKit.moderator()
        repeat(RACE_ROUNDS) { round ->
            val m = seedMemberScope(ScopeEntity.GROUP)
            val root = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            seedAttachment(m.scopeId, root, NodeCover.SLOT, uploaderUserId = m.userId)
            val share = GuestTreeShare.seed(m.userId, m.scopeId, root, ShareEntity.READ).shareId
            val first = OrderIn(
                id = UUID.randomUUID().toString(), kind = OrderKind.REMOVAL, shareId = share, receivedAt = Instant.now(), epoch = 1,
                sealedReference = SEALED_REFERENCE, holdDays = null, deferStatementUntil = null,
            )
            val firstAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val second = whileUncommitted(
                change = { assertNotNull(orders.enterIn(mod.id, first, firstAt).first, "round $round: the first order was refused") },
                overlapping = {
                    ModerationTestKit.asOwner(mod.id).body(orderBody(share)).post("/api/instance/moderation/orders")
                        .then().extract().response()
                },
            )
            assertEquals(201, second.statusCode, "round $round: the second order was refused — ${second.asString()}")
            assertEquals(firstAt, OffsetDateTime.parse(second.path<String>("blockedAt")).toInstant(), "round $round")
            QuarkusTransaction.requiringNew().run {
                assertEquals(1L, ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", share), "round $round: two share blocks")
                assertEquals(1L, ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", root), "round $round: two content blocks")
                assertEquals(2L, AuthorityOrderEntity.count("shareId", share), "round $round: both orders are recorded")
            }
        }
    }
}
