package com.veyq.core.adminchannel

import io.quarkus.arc.Arc
import io.restassured.RestAssured
import io.restassured.specification.RequestSpecification

/**
 * `given()`, aimed at the admin listener instead of the public port — the one way a test calls a Server-Owner
 * operation (uc.admin.admin-channel). The listener binds a free port under `%test`, so it is read back from the
 * running bean rather than configured per file.
 */
fun givenAdminChannel(): RequestSpecification =
    RestAssured.given().port(Arc.container().instance(AdminListener::class.java).get().port())
