package me.raddatz.veyq.mobile.crypto

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfc(s: String): String = (s as NSString).precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfkd(s: String): String = (s as NSString).decomposedStringWithCompatibilityMapping
