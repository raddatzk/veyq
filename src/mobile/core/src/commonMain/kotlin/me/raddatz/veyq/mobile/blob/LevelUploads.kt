package me.raddatz.veyq.mobile.blob

import me.raddatz.veyq.mobile.export.ImportDialog

/**
 * A level's upload (ADR 0138 decision 9, ADR 0154 decision 1): what a drop onto a level or a folder's row and *Add here* on
 * the level shown hand the files and folders to, so the platform's drop names no application (ADR 0156, inbox #1215).
 * Depot's uploads implement it (`DepotUploads`).
 */
interface LevelUploads {
    /**
     * [sources] uploaded as new files under [parentId] (null: the space's top) of [scopeId], the batch named [label]; each
     * handed to [left] once the queue lets it go. How many were queued.
     */
    fun upload(scopeId: String, parentId: String?, label: String, sources: List<UploadSource>, left: ((UploadSource) -> Unit)?): Int

    /** [upload] through the import's screen: a veyq download among [sources] offered as an import (one), the rest uploaded. */
    suspend fun screen(
        scopeId: String, parentId: String?, label: String, sources: List<UploadSource>, left: ((UploadSource) -> Unit)? = null, deletedName: String = "",
    ): Screened

    /** A folder at [path] ([shownName] its shown name), what its walk [found], through the import's screen as [screen]. */
    suspend fun screenFolder(
        scopeId: String, parentId: String?, label: String, shownName: String?, path: String, found: List<DirectoryWalk.Found>, deletedName: String = "",
    ): Screened

    /** A folder at [path] uploaded whole, what its walk [found]; how many files were queued. */
    suspend fun uploadFolder(scopeId: String, parentId: String?, shownName: String?, path: String, found: List<DirectoryWalk.Found>): Int

    /** What a screened upload came to: [queued] files, and the import it offers ([offer]; null: none). */
    class Screened(val queued: Int, val offer: ImportDialog?)
}
