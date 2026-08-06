package com.veyq.app.shared.http

import jakarta.ws.rs.core.HttpHeaders

/**
 * The bearer access token from the `Authorization` header, or null if absent or blank. The single front-door
 * parse every authenticated resource shares, so the missing/malformed-token boundary is decided in one place
 * (and stays consistent with the generic-401 posture) instead of being re-implemented per resource.
 */
fun bearerToken(headers: HttpHeaders): String? =
    headers.getHeaderString("Authorization")
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
