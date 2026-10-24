package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.util.UUID

/** The Expenses wire as a client speaks it, for this module's slice tests. */
object PotWire {
    /** A sealed body of the smallest size class whose every character is [mark], so a test can tell bodies apart. */
    fun body(mark: Char = 'A', length: Int = 744): String = mark.toString().repeat(length)

    const val WRAP = "d3JhcHBlZC1rLXJldg=="

    data class Rev(
        val entryId: String = UUID.randomUUID().toString(),
        val revision: Long = 1,
        val tombstone: Boolean = false,
        val body: String = body(),
        val wrap: String = WRAP,
    ) {
        fun json() = """{"entryId":"$entryId","revision":$revision,"tombstone":$tombstone,"sealedBody":"$body","wrappedRevisionKey":"$wrap"}"""
    }

    fun append(userId: String, scopeId: String, potId: String, revs: List<Rev>, nodeEpoch: Int = 0): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeEpoch":$nodeEpoch,"revisions":[${revs.joinToString(",") { it.json() }}]}""")
            .`when`().post("/api/scopes/$scopeId/pots/$potId/revisions").then()

    fun erase(userId: String, scopeId: String, potId: String, entryId: String, revision: Long, body: String = body('E')): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeEpoch":0,"entryId":"$entryId","revision":$revision,"sealedBody":"$body","wrappedRevisionKey":"$WRAP"}""")
            .`when`().post("/api/scopes/$scopeId/pots/$potId/erasures").then()

    fun list(userId: String, scopeId: String, potId: String, query: String = "", ifNoneMatch: String? = null): ValidatableResponse {
        var spec = given().header("Authorization", bearer(userId))
        if (ifNoneMatch != null) spec = spec.header("If-None-Match", ifNoneMatch)
        return spec.`when`().get("/api/scopes/$scopeId/pots/$potId/revisions$query").then()
    }

    fun guestAppend(link: GuestTreeShare.Link, potId: String, revs: List<Rev>, xff: String = "203.0.113.50:40000"): ValidatableResponse =
        given().contentType(ContentType.JSON).header("X-Forwarded-For", xff)
            .body("""{"nodeEpoch":0,"contributorToken":"${link.token}","revisions":[${revs.joinToString(",") { it.json() }}]}""")
            .`when`().post("/api/shares/${link.shareId}/pots/$potId/revisions").then()

    fun guestList(shareId: String, potId: String, query: String = "", ifNoneMatch: String? = null): ValidatableResponse {
        var spec = given()
        if (ifNoneMatch != null) spec = spec.header("If-None-Match", ifNoneMatch)
        return spec.`when`().get("/api/shares/$shareId/pots/$potId/revisions$query").then()
    }
}
