package me.raddatz.veyq.mobile.blob

/**
 * What a platform's walk of a picked or dropped directory meets (ADR 0137's Consequences; each walks through its own
 * door: Android's documents provider, Apple's enumerator, the desktops' `PickedPaths`): the platform's, since a directory
 * is dropped onto any page — what an application keeps of it is its own (Depot's folder upload, `FolderUpload.keeps`).
 */
object DirectoryWalk {
    /** What a walk met at one path: a file, a directory, a link, or an entry it could not read. */
    enum class Kind { FILE, DIRECTORY, LINK, UNREADABLE, OTHER }

    /** One entry a walk met: its path below the picked directory (`/`-separated), what it is, and for a file its [source]. */
    class Found(val path: String, val kind: Kind, val source: UploadSource? = null)

    /**
     * The name a walked directory is said and made by: the picked directory's own ([shown], what the platform's picker
     * names it), else the last part of its [path] (a path's or a document id's, parted at `/`, a backslash and `:`) — never
     * a word of the app's own. A dropped folder's name (`Dropped.Folder.name`) and Depot's uploaded folder's.
     */
    fun rootName(shown: String?, path: String): String {
        shown?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return path.split('/', '\\', ':').lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }
}
