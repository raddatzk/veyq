package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare.create
import me.raddatz.veyq.core.sharing.GuestTreeShare.place
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

/**
 * The per-share upload budget (ADR 0051) and a guest's tree operations: a create adds a node row and spends one token,
 * a placement adds none and spends nothing (`@RateLimit(key = SHARE)` / `@RateLimitExempt` on the two routes). Its own
 * profile: a budget of 2 per share, so the third create is the 429.
 */
@QuarkusTest
@TestProfile(ShareNodeBudgetCoreSliceTest.BudgetOfTwo::class)
class ShareNodeBudgetCoreSliceTest {

    class BudgetOfTwo : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "veyq.share.upload.rate.max-per-window" to "2",
            "veyq.share.upload.rate.window-seconds" to "3600",
        )
    }

    @Test
    fun aCreateSpendsOneTokenAndAPlacementNone() {
        val (userId, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val a = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val b = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, root)

        // three placements first: none spends a token, or the creates below would run out early
        place(link, a, b, placementVersion = 0).statusCode(204)
        place(link, a, root, placementVersion = 1).statusCode(204)
        place(link, a, b, placementVersion = 2).statusCode(204)
        create(link, root, NodeEntity.FOLDER).statusCode(201)
        create(link, root, NodeEntity.FOLDER).statusCode(201)
        create(link, root, NodeEntity.FOLDER).statusCode(429).body("code", equalTo("rate_limited"))
        // a spent budget stops no placement
        place(link, a, root, placementVersion = 3).statusCode(204)
    }
}
