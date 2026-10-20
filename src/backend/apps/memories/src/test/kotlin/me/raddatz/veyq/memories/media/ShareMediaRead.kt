package me.raddatz.veyq.memories.media

import io.restassured.RestAssured.given
import io.restassured.response.Response

/**
 * A guest's read of what a share link serves of the Memory's media — Memories' `listShareMedia`: the rows under the
 * shared subtree and the wrapped keys of the WRITE_ONLY identities whose rows those are (`media` + `identities`, read
 * raw, never through a generated client). The tests that pin what a guest is served — and what it is not (ADR 0030
 * point 8) — read it through this one call, so the route they pin is named once.
 */
object ShareMediaRead {
    fun of(shareId: String, pwToken: String? = null): Response =
        given().apply { if (pwToken != null) header("X-Share-Password", pwToken) }
            .`when`().get("/api/shares/$shareId/media")
}
