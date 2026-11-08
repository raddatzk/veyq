package me.raddatz.veyq.core.account.profile

import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The account's donation-banner dismissal (uc.platform.support-the-project, ADR 0122): a boolean on the account,
 * served on the caller's own profile and written through the profile update — omitted, it is left as it is; set, it
 * holds in every tenant of the account and for that account alone. Fresh tenants and accounts per test, so the
 * JVM-lived H2 database cannot bleed state between classes.
 */
@QuarkusTest
class DonationBannerSliceTest {

    @Inject lateinit var profiles: TenantProfileStore

    @Test
    fun theDismissalIsStoredOnTheAccountAndServedInEveryTenant() {
        val member = seedUser()
        val first = memberOf(member, "first")
        val second = memberOf(member, "second")

        get(member, first).body("donationBannerDismissed", equalTo(false))

        put(member, first, """{"donationBannerDismissed":true}""")
            .body("donationBannerDismissed", equalTo(true)).body("username", equalTo("first"))

        assertTrue(QuarkusTransaction.requiringNew().call { VeyqUser.findById(member)!!.donationBannerDismissed })
        get(member, first).body("donationBannerDismissed", equalTo(true))
        // Account-global: the other tenant's profile serves the same account's flag.
        get(member, second).body("donationBannerDismissed", equalTo(true))
    }

    @Test
    fun anUpdateThatOmitsTheFlagLeavesItAsItIs() {
        val member = seedUser()
        val domain = memberOf(member, "member")

        put(member, domain, """{"requireGroupAddConsent":true}""")
            .body("donationBannerDismissed", equalTo(false)).body("requireGroupAddConsent", equalTo(true))
        put(member, domain, """{"donationBannerDismissed":true}""")
            .body("donationBannerDismissed", equalTo(true)).body("requireGroupAddConsent", equalTo(true))
        put(member, domain, """{"username":"renamed","donationBannerDismissed":null}""")
            .body("donationBannerDismissed", equalTo(true)).body("username", equalTo("renamed"))
    }

    @Test
    fun oneAccountsDismissalIsNotAnothers() {
        val dismissing = seedUser()
        val domain = memberOf(dismissing, "dismissing")
        val other = seedUser()
        val tenantId = QuarkusTransaction.requiringNew().call {
            TenantEntity.find("domain", domain).firstResult()!!.id
        }
        QuarkusTransaction.requiringNew().run { profiles.create(other, tenantId, "other") }

        put(dismissing, domain, """{"donationBannerDismissed":true}""").body("donationBannerDismissed", equalTo(true))

        get(other, domain).body("donationBannerDismissed", equalTo(false))
        assertFalse(QuarkusTransaction.requiringNew().call { VeyqUser.findById(other)!!.donationBannerDismissed })
    }

    /** A fresh tenant [userId] owns, with its profile there under [username]; returns the tenant's domain. */
    private fun memberOf(userId: String, username: String): String {
        val id = UUID.randomUUID().toString()
        val domain = "t-$id.example.com"
        seedTenant(id = id, domain = domain, owner = userId)
        QuarkusTransaction.requiringNew().run { profiles.create(userId, id, username) }
        return domain
    }

    private fun get(userId: String, domain: String) =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain)
            .`when`().get("/api/account/profile")
            .then().statusCode(200)

    private fun put(userId: String, domain: String, body: String) =
        given().header("Authorization", bearer(userId)).header("X-Forwarded-Host", domain)
            .contentType(ContentType.JSON).body(body)
            .`when`().put("/api/account/profile")
            .then().statusCode(200)
}
