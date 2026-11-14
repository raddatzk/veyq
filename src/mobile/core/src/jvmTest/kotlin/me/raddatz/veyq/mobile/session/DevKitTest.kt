package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.qr.QrEncoder
import me.raddatz.veyq.mobile.qr.QrPng
import java.io.File
import kotlin.test.Test

/**
 * Dev tooling, not a test of the product: writes the seeded principal's Emergency Kit for a device pass of the apps'
 * kit sign-in (`cr-app-kit-sign-in` phases 6 and 7) — `dev-kit.pdf`, the token in its `Keywords` as
 * [LiveSignInTest.aKitPdfSignsThePrincipalInWithAnEmptySecretKey] builds it, for a picker, and `dev-kit-qr.png`, its QR
 * drawn by the core's [QrPng] (8 px a module), for a camera (Android's virtual-scene poster, Apple's `VEYQ_DEV_CAMERA`).
 * The password stays out: the pass hands it through `devlogin.py --kit`.
 *
 * Runs only with `VEYQ_DEV_KIT_OUT=<directory>` (a scratch directory the pass removes after; both files carry the
 * Secret Key) and `VEYQ_DEV_KIT_SERVER`, the kit's server as the app reaches it (default `http://veyq.localhost:8080`).
 * The principal is `VEYQ_ENV_FILE`'s, or this checkout's `.run/.env`. Nothing of the kit is printed.
 */
class DevKitTest {
    @Test
    fun writeThePrincipalsKitForADevicePass() {
        val out = System.getenv("VEYQ_DEV_KIT_OUT")?.let(::File) ?: return
        val server = System.getenv("VEYQ_DEV_KIT_SERVER") ?: "http://veyq.localhost:8080"
        val env = (System.getenv("VEYQ_ENV_FILE")?.let(::File) ?: File(System.getProperty("veyq.repoRoot"), ".run/.env")).readLines()
            .mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { it.trim() to l.substringAfter('=').trim().trim('"', '\'') } }
            .toMap()
        out.mkdirs()
        val token = LiveSignInTest.kitToken(env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_SECRET_KEY"), server)
        File(out, "dev-kit.pdf").writeBytes(LiveSignInTest.kitPdf(token))
        File(out, "dev-kit-qr.png").writeBytes(QrPng.write(QrEncoder.encode(token), 8))
        println("dev kit: written for $server")
    }
}
