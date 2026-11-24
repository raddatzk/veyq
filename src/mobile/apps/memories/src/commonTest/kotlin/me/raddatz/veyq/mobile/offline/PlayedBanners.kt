package me.raddatz.veyq.mobile.offline

import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.memories.banner.Banner

/**
 * Memories' half of the played server ([PlayedTenant], `:testing`, which names no application): node [n]'s banner set — a
 * video of [frames] frames with a one-frame poster, or (not [video]) a one-frame image, each with a marker. Its meta names
 * them as `BannerWrite` seals it, made when the node is served (each blob's wrap follows the node's key); the tree's
 * revision moves.
 */
internal fun PlayedTenant.banner(n: PlayedTenant.Node, label: String, video: Boolean = true, frames: Int = 2): Pair<PlayedTenant.Blob, PlayedTenant.Blob?> {
    val main = Blob(label, (0 until (if (video) frames else 1)).map { "banner $label frame $it".encodeToByteArray() }, "BANNER", n.id, { n.key }, n.scope.id, null)
    val poster = if (video) Blob("$label-poster", listOf("poster $label".encodeToByteArray()), "BANNER_POSTER", n.id, { n.key }, n.scope.id, null) else null
    keep(main)
    poster?.let { keep(it) }
    fun ref(b: PlayedTenant.Blob) = Banner.Ref(b.id, b.frames.size, b.byteSize, b64(b.wrapped))
    n.metaServed["banner"] = {
        Banner.sealed(if (poster != null) Banner.Medium.VIDEO else Banner.Medium.IMAGE, ref(main), poster?.let(::ref), "video/mp4", null)
    }
    n.scope.revision++
    return main to poster
}
