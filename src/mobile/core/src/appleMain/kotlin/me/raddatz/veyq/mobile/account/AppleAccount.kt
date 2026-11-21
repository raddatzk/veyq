package me.raddatz.veyq.mobile.account

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import me.raddatz.veyq.mobile.navigation.TenantRef
import me.raddatz.veyq.mobile.session.AddressChange
import me.raddatz.veyq.mobile.session.ProfileModel
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.addressChange
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The account's flows as the Apple app reaches them (ADR 0148 decision 10, `apple/Veyq/Core/AccountFlows.swift`): what Swift
 * cannot write itself — Kotlin's default arguments, a picture's or a
 * kit's bytes in one copy across the language boundary instead of byte by byte.
 */
object AppleAccount {
    /** One's own profile on [tenant] with the core's WebP encoder (libwebp's, ADR 0148 decision 2). */
    fun profile(tenant: TenantSession): ProfileModel = tenant.profile()

    /** The address change of [account], its mail sent through [tenant]'s origin (the tenant on screen). */
    fun addressChange(account: VeyqAccount, tenant: TenantRef?): AddressChange = account.addressChange(tenant ?: account.defaultTenant)

    /** The picture [profile] draws now (the draft's, else the one the pictures hold), in one copy; null: the letter. */
    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    fun picture(profile: ProfileModel): NSData? {
        val bytes = profile.picture() ?: return null
        if (bytes.isEmpty()) return null
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }

    /** The crop the host rendered ([rgba], [ProfileModel.EDGE] square, 4 bytes a pixel) handed to [profile], then zeroed. */
    suspend fun choosePicture(profile: ProfileModel, rgba: NSData) {
        val bytes = AppleKit.bytes(rgba)
        try {
            profile.choosePicture(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * iOS's save of a kit (ADR 0148 decision 5): `fileExporter` takes a document, not a path, so the core writes the PDF
     * into one buffer here ([KitStep.saveInto], whose own bytes it zeroes) that the Swift side hands the exporter and
     * zeroes once the export ended. Null where the write failed ([KitStep.State.refusal]). A Mac saves by path
     * ([KitStep.save]) into the file its save panel chose.
     */
    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    fun kitData(step: KitStep, language: String, createdEpochDay: Long): NSMutableData? {
        var out: NSMutableData? = null
        val written = step.saveInto(language, createdEpochDay) { bytes ->
            val data = NSMutableData.create(length = bytes.size.toULong()) ?: error("no buffer for the kit")
            bytes.usePinned { memcpy(data.mutableBytes, it.addressOf(0), bytes.size.toULong()) }
            out = data
        }
        return if (written) out else null
    }
}
