package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Slice tests for contributor public shares (uc.sharing.share-contributor, cr-0101, ADR 0030) against the
 * **in-memory store** (metadata only — the byte path is direct-to-store, covered by the store e2e). Covers: a
 * READ_WRITE share's anonymous **identity-create** (name sealed under `K_node`, opaque) → **share-scoped upload**
 * (no bearer, gated by role + token) → the media **attaches to the shared root stamped with the identity** and
 * surfaces in the anonymous resolve (with no identity and no name — those reach the curator's bucket list only,
 * ADR 0030 point 8; the full per-role matrix is `ShareGuestNamesSliceTest`); a **READ** share rejects the identity
 * path (`403`); a **bad token** is rejected (`403`); **revoke** stops contributions (`404`); operator-blindness
 * (the sealed name + wrap round-trip, the server stores only ciphertext + opaque ids).
 *
 * **Split from `ShareContributorSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `ShareContributorSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class ShareContributorCoreSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="

    private val sealedContributorName = "c2VhbGVkLWNvbnRyaWJ1dG9yLW5hbWU="

    @Test
    fun unknownShareIdentityCreateIs404() {
        given().contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedContributorName"}""")
            .`when`().post("/api/shares/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/identities")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    // --- helpers ---
}
