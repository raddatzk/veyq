package me.raddatz.veyq.mobile.qr

/**
 * The schemes veyq draws its codes under (the web's `veyqpair:`, `veyqsignin:` and the Emergency Kit's `veyqkit:`, the safety number's `veyqid:`): what
 * a scan that takes any veyq code accepts a code as — the desktops' `Scan.Open` of kind `SCHEME`, their debug harness's
 * (the QR CR, phase 5). Only the scheme leaves; a caller that needs the code's meaning has its own parser (the kit's
 * `EmergencyKit.read`, the pairing's).
 */
object QrSchemes {
    val ALL: List<String> = listOf("veyqpair", "veyqsignin", "veyqkit", "veyqid")

    /** [text]'s scheme when it is one of [ALL], its colon and something after it; null for any other code. */
    fun of(text: String): String? = ALL.firstOrNull { text.length > it.length + 1 && text.startsWith("$it:") }
}
