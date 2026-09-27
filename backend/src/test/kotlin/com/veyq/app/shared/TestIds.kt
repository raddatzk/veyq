package com.veyq.app.shared

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * The ids a client mints for what it creates (ADR 0072 Decision 3): a UUIDv4 for every object, and a share's
 * 43-character base64url bearer token, which stays a 256-bit capability. Slice tests mint through here so a create
 * body carries exactly the shape the web client sends.
 */
object TestIds {
    private val random = SecureRandom()

    fun uuid(): String = UUID.randomUUID().toString()

    fun shareId(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
}
