package me.raddatz.veyq.mobile.crypto

// `String.prototype.normalize`, the web's own (`primitives.ts` `utf8`): every engine the web supports carries the forms.
actual fun nfc(s: String): String = s.asDynamic().normalize("NFC").unsafeCast<String>()

actual fun nfkd(s: String): String = s.asDynamic().normalize("NFKD").unsafeCast<String>()

actual fun nfd(s: String): String = s.asDynamic().normalize("NFD").unsafeCast<String>()
