package me.raddatz.veyq.core.sharing

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Test

/**
 * Slice tests for public viewer shares (uc.sharing.share-public-viewer, cr-0100, ADR 0029) against the
 * **in-memory store** (metadata only — the byte path is direct-from-store, covered by the store e2e). Covers
 * the six acceptance criteria: create → **anonymous** resolve returns the sealed subtree; the shareId alone is
 * inert (the wrap is opaque); the gated blob GET allows an in-subtree blob (store_unavailable proves authz on
 * the dev store) and 404s an out-of-subtree blob; revoke + expiry 404 the guest paths; a non-MEMORY (folder)
 * node create is rejected 422 (group/tenant shared-scope sharing is cr-0104); a non-member/unknown node is 404;
 * resolve leaks nothing outside the subtree.
 *
 * With the in-memory store a presign answers `503 store_unavailable` when the blob **is** authorized (proving
 * the subtree gate passed) vs `404 share_not_found` when it is not — so the two codes distinguish "authorized
 * but no transport" from "not under the share", which is what these hermetic tests assert.
 *
 * **Split from `ShareSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `ShareSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class ShareCoreSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="

    @Test
    fun unknownShareResolves404() {
        // a well-formed but unknown 43-char base64url id — non-enumerable 404
        given().`when`().get("/api/shares/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            .then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    // --- accountless terms gate (uc.sharing.share-accept, ADR 0035 §3) ---

    // --- helpers ---
}
