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
}
