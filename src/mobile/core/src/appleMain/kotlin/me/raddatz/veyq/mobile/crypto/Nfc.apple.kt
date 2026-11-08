package me.raddatz.veyq.mobile.crypto

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfc(s: String): String = (s as NSString).precomposedStringWithCanonicalMapping
