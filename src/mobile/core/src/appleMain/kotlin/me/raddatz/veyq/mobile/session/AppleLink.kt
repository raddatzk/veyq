package me.raddatz.veyq.mobile.session

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import me.raddatz.veyq.mobile.qr.QrFrames
import me.raddatz.veyq.mobile.qr.QrScan
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes

/**
 * Device linking as the Apple app reaches it (`apple/Veyq/Core/DeviceLinkView.swift`): Swift
 * sees neither Kotlin's default arguments nor a suspending decoder, its camera takes a `QrScan<AnyObject>`, and a
 * Kotlin byte array crosses the boundary a byte at a time — so the four roles are opened here, each decode's time goes
 * to [timing] (never the text), and the code's picture comes back as one `NSData`.
 */
object AppleLink {
    /** Settings' "Show a Code": the trusted [account]'s code for the new device to scan. */
    fun showForNewDevice(account: VeyqAccount): DeviceLink = DeviceLink.showForNewDevice(account)

    /** Settings' "Scan a Code": the new device's sign-in code, scanned by the trusted [account]. */
    fun scanSignInCode(account: VeyqAccount, timing: (width: Int, height: Int, millis: Double, read: Boolean) -> Unit): DeviceLink =
        DeviceLink.scanSignInCode(account.primitives, AccountGranter(account), QrFrames.timedDecode(timing))

    /** The sign-in's "Scan a Code from Another Device" over the form's [server]; null outside the sign-in. */
    fun scanFromTrusted(
        session: SessionModel<VeyqAccount, TenantSession>,
        server: String,
        timing: (width: Int, height: Int, millis: Double, read: Boolean) -> Unit,
    ): DeviceLink? = session.scanFromTrusted(server, QrFrames.timedDecode(timing))

    /** The sign-in's "Sign In with a QR Code" over the form's [server]; null outside the sign-in. */
    fun showForSignIn(session: SessionModel<VeyqAccount, TenantSession>, server: String): DeviceLink? = session.showForSignIn(server)

    /** The link's camera scan as the Apple camera takes it, or null for a showing role. */
    @Suppress("UNCHECKED_CAST")
    fun scan(link: DeviceLink): QrScan<Any>? = link.scan as QrScan<Any>?

    /** This side's code as a PNG about [px] pixels wide, in one copy; null where it shows none. */
    @OptIn(ExperimentalForeignApi::class)
    fun png(link: DeviceLink, px: Int): NSData? {
        val bytes = link.png(px) ?: return null
        return bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
    }
}
