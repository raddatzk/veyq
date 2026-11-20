@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.sharing

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import me.raddatz.veyq.mobile.wincred.veyq_vault_read
import me.raddatz.veyq.mobile.wincred.veyq_vault_remove
import me.raddatz.veyq.mobile.wincred.veyq_vault_write

/**
 * [GuestSessions] on Windows (ADR 0150 decision 3; inbox #1019): a received link's guest session is a credential of the
 * Credential Locker (WinRT's `Windows.Security.Credentials.PasswordVault`, through `wincred/veyq_wincred.h`), the user's
 * own vault, sealed by Windows for this account as the web's and the apps' other secrets are kept by their platform. Its
 * resource is the name [GuestNames.session] gives — a truncated hash of the link's origin and id, never the share's id —,
 * its user name `veyq`, its password the session. PowerShell lists the names alone with
 * `(New-Object Windows.Security.Credentials.PasswordVault).RetrieveAll() | Select Resource, UserName`.
 *
 * A write the vault refuses throws [GuestSessionNotKept] (which the naming reports), a read that finds nothing is null and
 * a remove of nothing does nothing: the guest names itself anew on the next visit, as the web's guest in a private window
 * does. A session never goes to the device store (`DeviceStore` holds no secret).
 */
class WindowsGuestSessions internal constructor(
    /** Prefixed to every credential's resource: empty for the app's sessions; a test's own, so its credentials never meet another's. */
    private val namespace: String,
) : GuestSessions {
    /** The app's guest sessions, in the signed-in Windows user's Credential Locker. */
    constructor() : this("")

    override fun read(name: String): String? {
        var cap = CAPACITY
        while (true) {
            val (status, text) = memScoped {
                val buf = allocArray<UShortVar>(cap)
                val len = alloc<IntVar>()
                val status = veyq_vault_read(resource(name).wcstr.ptr, buf, cap, len.ptr)
                when {
                    status == INSUFFICIENT_BUFFER && len.value > cap -> {
                        cap = len.value
                        status to null
                    }
                    status == 0 -> status to CharArray(len.value) { buf[it].toInt().toChar() }.concatToString()
                    else -> status to null
                }
            }
            if (status != INSUFFICIENT_BUFFER) return text
        }
    }

    override fun write(name: String, value: String) {
        val status = memScoped { veyq_vault_write(resource(name).wcstr.ptr, value.wcstr.ptr) }
        if (status != 0) throw GuestSessionNotKept("the Credential Locker refused it (0x${status.toUInt().toString(16)})")
    }

    override fun remove(name: String) {
        memScoped { veyq_vault_remove(resource(name).wcstr.ptr) }
    }

    private fun resource(name: String) = namespace + name

    internal companion object {
        /** UTF-16 units read at first; a longer session is read again at its length. */
        const val CAPACITY = 4096

        /** HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER): the session is longer than the buffer handed in. */
        val INSUFFICIENT_BUFFER = 0x8007007A.toInt()
    }
}

/** A guest session the Credential Locker did not keep: its reason (never the session's value). */
class GuestSessionNotKept(reason: String) : IllegalStateException("guest session not kept: $reason")
