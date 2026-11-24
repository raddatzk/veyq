package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.NodeCreate

// Depot's pages over the platform's shell (ADR 0156, inbox #1215): a file's page, the uploads, a drop box's page —
// extensions of [ShellModel], so every client calls them where it did (Swift: methods of a category on `ShellModel`;
// Kotlin: imported from this package). They read the shell through the members it opens to its applications
// (`@PlatformApi`).

/**
 * The file [ref]'s page shows over this tenant ([FileModel]): the file read anew on each [FileModel.reload], its content
 * in frames and ranges, its uploader, its replace as a job of the upload queue; [shares] where the platform has a
 * share sheet ("Send a copy").
 */
fun ShellModel.fileModel(ref: NodeAt, shares: Boolean): me.raddatz.veyq.mobile.depot.FileModel =
    me.raddatz.veyq.mobile.depot.FileModel(ref, object : me.raddatz.veyq.mobile.depot.FileModel.Door {
        override suspend fun file(node: NodeAt) = me.raddatz.veyq.mobile.depot.DepotFiles.one(tenant, node.scopeId, node.nodeId)
        override fun inIdentityFolder(node: NodeAt) = this@fileModel.inIdentityFolder(node)
        override suspend fun read(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, first: Int, until: Int, sink: suspend (ByteArray) -> Unit) =
            me.raddatz.veyq.mobile.depot.DepotFiles.read(tenant, file, first, until, sink)
        override fun reader(file: me.raddatz.veyq.mobile.depot.DepotFiles.File) = me.raddatz.veyq.mobile.depot.DepotFiles.reader(tenant, file)
        override suspend fun uploader(file: me.raddatz.veyq.mobile.depot.DepotFiles.File) = me.raddatz.veyq.mobile.depot.Uploaders.of(tenant, file)
        override fun replaceJob(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, source: me.raddatz.veyq.mobile.blob.UploadSource, expectedBlobId: String?) =
            me.raddatz.veyq.mobile.depot.DepotFiles.ReplaceJob(tenant, file, source, expectedBlobId, source.name)
        override fun online() = this@fileModel.online
    }, shares)

/**
 * The uploads of this tenant over the app's [queue] ([me.raddatz.veyq.mobile.depot.DepotUploads]): files and folders
 * uploaded into a level, a file's replace, the settle's offer; [changed] after each write (the app reads its lists anew).
 * A download is uploaded as the file it is: the phones offer no import for now (owner 2026-11-19).
 */
fun ShellModel.depotUploads(queue: me.raddatz.veyq.mobile.blob.UploadQueue, changed: () -> Unit): me.raddatz.veyq.mobile.depot.DepotUploads =
    depotUploads(queue, imports = false, deriver = null, changed = changed)

/**
 * [depotUploads] where the platform offers a download's import at its uploads (ADR 0152 decision 1: the desktops),
 * each imported medium's tiers made by its [deriver].
 */
fun ShellModel.depotUploadsImporting(
    queue: me.raddatz.veyq.mobile.blob.UploadQueue, deriver: me.raddatz.veyq.mobile.media.Derivation.Deriver?, changed: () -> Unit,
): me.raddatz.veyq.mobile.depot.DepotUploads = depotUploads(queue, imports = true, deriver = deriver, changed = changed)

private fun ShellModel.depotUploads(
    queue: me.raddatz.veyq.mobile.blob.UploadQueue, imports: Boolean,
    deriver: me.raddatz.veyq.mobile.media.Derivation.Deriver?, changed: () -> Unit,
): me.raddatz.veyq.mobile.depot.DepotUploads =
    me.raddatz.veyq.mobile.depot.DepotUploads(queue, object : me.raddatz.veyq.mobile.depot.DepotUploads.Door {
        override fun uploadJob(scopeId: String, parentId: String?, source: me.raddatz.veyq.mobile.blob.UploadSource, name: String) =
            me.raddatz.veyq.mobile.depot.DepotFiles.UploadJob(tenant, scopeId, parentId, source, AppKinds::tokenInput, name)
        override suspend fun prepareFolder(scopeId: String, parentId: String?, rootName: String, entries: List<me.raddatz.veyq.mobile.depot.FolderUpload.Entry>) =
            me.raddatz.veyq.mobile.depot.FolderUpload.prepare(tenant, scopeId, parentId, rootName, entries, AppKinds::tokenInput)
        override suspend fun file(scopeId: String, nodeId: String) = me.raddatz.veyq.mobile.depot.DepotFiles.one(tenant, scopeId, nodeId)
        override fun replaceJob(file: me.raddatz.veyq.mobile.depot.DepotFiles.File, source: me.raddatz.veyq.mobile.blob.UploadSource, expectedBlobId: String?) =
            me.raddatz.veyq.mobile.depot.DepotFiles.ReplaceJob(tenant, file, source, expectedBlobId, source.name)
        override fun changed() = changed()
        override fun online() = this@depotUploads.online
        override val queues get() = tenant.account.queues(me.raddatz.veyq.mobile.offline.Queued.DEPOT_FILE_ADD)
        override suspend fun queue(
            scopeId: String, parentId: String?, source: me.raddatz.veyq.mobile.blob.UploadSource, name: String,
            left: (me.raddatz.veyq.mobile.blob.UploadSource) -> Unit,
        ) = queues(me.raddatz.veyq.mobile.offline.Queued.DEPOT_FILE_ADD) &&
            me.raddatz.veyq.mobile.depot.QueuedDepot.file(tenant.account, tenantId, scopeId, parentId, source, wallClock(), left, name) != null
        // a member's level: a space's top, or a folder that is no guest's (the web's `folderRights` createFolder)
        override fun mayImport(scopeId: String, parentId: String?) =
            this@depotUploads.online && (parentId == null || !tenant.inIdentityFolder(scopeId, parentId))
        override fun importDialog(
            scopeId: String, parentId: String?, target: String, input: me.raddatz.veyq.mobile.export.ImportInput,
            asFile: (() -> Unit)?, closed: () -> Unit, deletedName: String,
        ) = me.raddatz.veyq.mobile.export.ImportDialog(
            input, target, scopeId, parentId, me.raddatz.veyq.mobile.export.SubtreeImport.Server(tenant, scopeId),
            AppKinds.imports(deletedName, { NodeCreate.uuid(tenant.account) }, deriver, now), AppKinds::tokenInput, asFile, closed,
            { tenant.forgetTree(); changed() },
        ).also { it.start() }
    }, importOffered = imports)

/**
 * Drop box [ref]'s page over this tenant ([me.raddatz.veyq.mobile.depot.DropBoxPage]): its guests' folders, read anew on
 * every revision of these lists.
 */
fun ShellModel.dropBoxPage(ref: NodeAt): me.raddatz.veyq.mobile.depot.DropBoxPage =
    me.raddatz.veyq.mobile.depot.DropBoxPage(ref, object : me.raddatz.veyq.mobile.depot.DropBoxPage.Door {
        override suspend fun folders() = me.raddatz.veyq.mobile.depot.DropBoxes.folders(tenant, ref.scopeId, ref.nodeId)
        // each read of the lists is a new one (a rebuild after a refresh); a move of the shell alone keeps them
        override val revisions = state.map { it?.lists }.distinctUntilChanged { a, b -> a === b }
        // a guest's new folder: the scope's tree moved, the lists are read anew and the page with them
        override suspend fun treeMoved() = tenant.treeMoved(ref.scopeId).also { if (it) refresh() }
    })
