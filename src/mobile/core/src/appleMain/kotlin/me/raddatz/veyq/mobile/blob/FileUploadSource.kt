package me.raddatz.veyq.mobile.blob

import platform.Foundation.NSURL

/**
 * A picked file on an Apple device read for an upload: core's [FileSource] at [url]'s path. A file the system's picker
 * handed over is outside the app's sandbox: its security scope — [url]'s own, or [scope]'s, the directory a folder upload
 * picked — is entered while it is open, so the queue may read it long after the pick.
 */
class FileUploadSource(
    val url: NSURL, private val scope: NSURL?, name: String, size: Long, type: String?, modifiedAt: Long?,
) : FileSource(url.path.orEmpty(), name, size, type, modifiedAt) {
    private var scoped = false

    override fun opening() {
        if (url.path == null) throw IllegalStateException("not a file: $url")
        scoped = (scope ?: url).startAccessingSecurityScopedResource()
    }

    override fun closing() {
        if (scoped) (scope ?: url).stopAccessingSecurityScopedResource()
        scoped = false
    }
}
