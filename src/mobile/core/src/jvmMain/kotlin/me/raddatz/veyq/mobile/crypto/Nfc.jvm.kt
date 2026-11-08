package me.raddatz.veyq.mobile.crypto

import java.text.Normalizer

actual fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

actual fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
