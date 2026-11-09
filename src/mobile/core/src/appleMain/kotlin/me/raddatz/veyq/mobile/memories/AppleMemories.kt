package me.raddatz.veyq.mobile.memories

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.session.TenantSession
import platform.Foundation.NSData
import platform.Foundation.create

/** Memories' media for the Apple app: a tier's plaintext as `NSData`, read off the main thread (`MemoryMedia.read`). */
@OptIn(ExperimentalForeignApi::class)
object AppleMemories {
    /** [blob] of [item], whole (a tile, a preview); null where it does not read here. */
    @Throws(Exception::class)
    suspend fun read(tenant: TenantSession, item: MemoryMedia.Item, blob: MemoryMedia.Blob): NSData? = withContext(Dispatchers.Default) {
        val bytes = MemoryMedia.read(tenant, item, blob) ?: return@withContext null
        if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }
}
