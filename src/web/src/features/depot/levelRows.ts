// Depot's part of the unified level list (inbox #407, ADR 0079): the ROW DATA of a folder level's `FILE` and `DEPOT_DROPBOX`
// children, and the flows behind their actions. Core draws the list (`core/node/LevelView.vue`, `LevelRowItem.vue`); this
// factory is called once per level view (`register.ts` names it as both kinds' `list.rows`) and returns what core needs per
// node (`LevelRowSource`). It replaces the former `FilesSection.vue` and `DropBoxSection.vue`; what those did beyond drawing
// lives here:
//
// - **The blobs of the level's files** (the source's listing: size, the sealed modified time, "not uploaded yet"), re-read
//   when the folder or the files change and on the level's live tick (inbox #408): a peer's upload completes WITHOUT the
//   tree changing (the node is listed first, its content after), and a peer's replace swaps a blob under an unchanged node.
// - **Upload and replace.** One core-queue job per picked or dropped file (`uploadFile.ts`): the top bar's status menu shows
//   its progress. "Upload files" is a level action (`depot.upload`, a "New" menu entry, inbox #406) registered with the level
//   for as long as it is mounted - so it does not depend on which tab is shown - and it takes dropped files too (`drop`,
//   which core calls only while the action is enabled). There is no replace action (owner decision 2026-10-14): a picked
//   file whose name a FILE here holds replaces that file's content, for a member and a contributor guest alike, after the
//   one question (inbox #383: the old content cannot be restored), once per such file; a file that holds no content asks
//   nothing (#384), nor does "replace anyway" after a peer's replace. A picked name that a sibling of ANOTHER kind holds (a
//   folder, a Memory, a drop box - each folded by its own kind) is not sent at all, for a member and a guest alike: a
//   notice names that sibling instead (inbox #400).
// - **Upload folder…** (inbox #424): a second level action (`depot.uploadFolder`) whose picker picks a DIRECTORY
//   (`webkitdirectory`), and which takes a dropped directory too (`dropDirectory`). Core looks first (`host.screenDirectory`):
//   a download unpacked is, for a member, the import; anything else - a guest's always - is uploaded as its tree
//   (`uploadFolder.ts`), after the quota preflight (a member's: the tenant's yes or no for the tree's bytes; a "no" writes
//   nothing, an unanswered question never blocks). A guest, who cannot trash, takes back the folders left empty once the
//   batch settled (a full link refused every file).
// - **The notices** only this application can explain: why a file of THIS folder was not uploaded, with the one remedy
//   there is (replace anyway after a peer's replace).
// - **The download**, a file's one action of its own. Rename, move and delete are core's defaults (`standardRowActions.ts`):
//   a row answers its rights for THAT node in `standard` and core runs them through the page's doors - its name and move
//   dialogs, and the delete question with the kind's own sentence (`register.ts`' `deleteTitle`/`deleteCopy`) and its Undo.
//
// What a row offers is Depot's rights matrix for the level (`./rights.ts`, inbox #401) narrowed to the doors the
// session's source carries (`./source.ts`): a member and a share guest get the same rows, a guest fewer actions. Every action
// is `shown` by that answer - fail closed. A row's primary click OPENS the node (a file's own page `FileDetail`, a drop box's
// `DropBoxDetail`); the file's download is its one first-class action.
//
// Operator-blind: the names, sizes, types and dates shown are opened here under keys the server never sees; Depot's calls are
// the listing (ids, sealed sizes and each blob's sealed meta) and the completion (ids only).
import { computed, ref, watch, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LevelNotice, LevelRow, LevelRowAction, LevelRowDetail, LevelRowSource, LevelRowsContext, LevelRowsHost } from '../../core/node/levelRow'
import type { TreeNode } from '../../core/node/tree'
import { collidingSibling, nameTakenMessage } from '../../core/node/siblingCollision'
import { enqueue, removeFile, useUploadQueue, APPLICATION_OFF, QUOTA_EXCEEDED, type UploadItem } from '../../core/blob/queue'
import { activeSpace } from '../../app/activeSpace'
import { useConfirm } from '../../shared/state/useConfirm'
import { useToast } from '../../shared/state/useToast'
import { formatBytes } from '../../shared/formatBytes'
import type { FileBlobRef } from '../../api/generated/depot'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { deliveredSince, FileUploadError, type FileJobExtra, type FileUploadResult } from './uploadFile'
import { fileFacts } from './download'
import { plaintextTotal } from '../../sw/range'
import { isSaveCancelled, SaveTooLarge } from '../../shared/saveStream'
import { canUploadFolder, depotRights } from './rights'
import { rowRightsOf, targetOf, type GuestAccess, type TreeSource } from '../../core/access'
import { pickedDirectory, type DirectoryInput } from '../../core/export/importSource'
import { tenantStorageFits } from '../tenant/tenantStorage'
import { createFolders, planFolderUpload, takeBackEmptyFolders } from './uploadFolder'
import { guestDepotSource, memberDepotSource, sourceRights, type FileUploadEntry, type DepotSource } from './source'

/** The id of Depot's "Upload files" level action (`register.ts` declares the menu entry under it). */
export const UPLOAD_ACTION = 'depot.upload'
/** The id of Depot's "Upload folder…" level action (inbox #424). */
export const UPLOAD_FOLDER_ACTION = 'depot.uploadFolder'

export function depotRows(ctx: Readonly<Ref<LevelRowsContext>>, host: LevelRowsHost): LevelRowSource {
  // One session for the source's life: a member's level or a link's page, never both - so the source is fixed at setup.
  const guest = ctx.value.access.mode === 'guest'
  const source: DepotSource = guest
    ? guestDepotSource(
        (): GuestAccess => {
          const a = ctx.value.access
          if (a.mode !== 'guest') throw new Error('a guest level was handed a member access')
          return a
        },
        host.move ? { move: (node) => host.move!(node) } : {},
      )
    : memberDepotSource({
        scopeId: () => activeSpace.scopeId,
        epochs: () => ctx.value.epochs,
        keyOf: (id) => ctx.value.tree.keyOf(id),
        // The page's doors (an absent one: the source has none, and the matrix narrowed by it offers nothing).
        ...(host.rename ? { rename: (node: TreeNode) => host.rename!(node) } : {}),
        ...(host.move ? { move: (node: TreeNode) => host.move!(node) } : {}),
      })

  const { t, locale } = useI18n()
  const { ask } = useConfirm()
  const toast = useToast()
  const { batches } = useUploadQueue()

  const folderId = () => ctx.value.folder?.id ?? null
  /** Only this application's nodes, whatever the level hands in. */
  const fileNodes = computed(() => ctx.value.nodes.filter((n) => n.kind === FILE_KIND))
  const boxNodes = computed(() => ctx.value.nodes.filter((n) => n.kind === DROPBOX_KIND))
  /** What the matrix says for the level on screen, narrowed to the doors this session's source carries. */
  const rights = computed(() => sourceRights(depotRights(ctx.value.access, targetOf(ctx.value.tree, ctx.value.folder)), source))
  /** The session can write now (a member's epoch keys are unlocked). */
  const writeReady = computed(() => source.upload?.ready() ?? false)
  const keyOf = (node: TreeNode): Uint8Array | null => ctx.value.tree.keyOf(node.id)

  // --- the blobs of this level's files (the source's listing) -------------------------------------------------------

  const blobs = ref(new Map<string, FileBlobRef | null>())
  let loads = 0
  /** Re-read the blob refs of this level's files. Only when there is a file: an empty level asks the server nothing. A
   *  later load wins over an earlier one still in flight. A `background` load (the live tick) never blanks on failure. */
  async function loadBlobs(background = false): Promise<void> {
    const mine = ++loads
    if (!source.scopeId() || fileNodes.value.length === 0) {
      blobs.value = new Map()
      return
    }
    try {
      const listed = await source.listBlobs(folderId())
      if (mine === loads) blobs.value = listed
    } catch {
      // The rows still show names; size falls back to nothing and download is unavailable until the next load. A
      // background refresh (the live tick) keeps what the page already shows instead: a blip must not blank it.
      if (mine === loads && !background) blobs.value = new Map()
    }
  }
  // Each part is its own computed, so the watch fires when one of THEM moved - not whenever the level's context (which also
  // carries the live tick and the view mode) is rebuilt.
  const listKey = computed(() => source.listKey())
  const folderKey = computed(folderId)
  const fileIds = computed(() => fileNodes.value.map((n) => n.id).join(','))
  watch([listKey, folderKey, fileIds], () => void loadBlobs(), { immediate: true })
  // The level's live refresh (inbox #408) says "look again" with its tick. Only a level that holds files asks.
  watch(
    () => ctx.value.liveTick,
    (tick, before) => {
      if (tick !== undefined && tick !== before && fileNodes.value.length > 0) void loadBlobs(true)
    },
  )

  // --- the rows -----------------------------------------------------------------------------------------------------

  const dateFormat = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))

  /** Whether the file is KNOWN to hold no content: listed without a blob (not merely unlisted after a failed read). */
  const isEmpty = (node: TreeNode): boolean => blobs.value.has(node.id) && blobs.value.get(node.id) === null

  /** A row's details from its BLOB alone (inbox #397): the plaintext size (exact, from the sealed size) and the modified time
   *  sealed in the blob's meta - present or not, the node's own payload is never read for it, so a file shows its facts
   *  whatever its node meta holds, and a replace shows the new blob's at once. No blob: not uploaded. */
  function fileDetails(key: Uint8Array | null, nodeId: string, blob: FileBlobRef | null): LevelRowDetail[] {
    if (!blob) return [{ id: 'state', text: t('depot.notUploaded') }]
    const scopeId = source.scopeId()
    const facts = key && scopeId ? fileFacts(scopeId, key, nodeId, blob) : null
    const size = facts?.size ?? Math.max(0, plaintextTotal(blob.byteSize, blob.chunkCount))
    const modifiedAt = facts?.meta?.modifiedAt
    return [
      { id: 'size', text: formatBytes(size, locale.value) },
      // The sealed modified time may be missing (a client that did not write it): then it is simply not shown.
      ...(modifiedAt === undefined ? [] : [{ id: 'modified', text: t('depot.modified', { date: dateFormat.value.format(modifiedAt) }) }]),
    ]
  }

  // --- upload and replace -------------------------------------------------------------------------------------------

  // The picker is built, never drawn: a level has no markup of its own to hold an `<input type="file">`, and a detached
  // input opens the browser's picker on `click()` like an attached one.
  const picker = document.createElement('input')
  picker.type = 'file'
  picker.multiple = true

  const batchKey = computed(() => source.batchKey(folderId()))

  /** The sibling of ANY kind a picked name collides with (inbox #400): what the server compares - a file's name folded by
   *  the file's function, each sibling's by its own kind's (core's `collidingSibling`), so `Foto.JPG` meets `foto.jpg` and
   *  a file `a-b` meets a folder `a.b` - or null. Read from the tree, which holds every kind of this level. */
  function sibling(name: string): TreeNode | null {
    const parentId = folderId()
    // Both sessions' trees serve decrypted `TreeNode`s (the guest's `guestTreeSource` too).
    return collidingSibling((ctx.value.tree as TreeSource<TreeNode>).children(parentId), parentId, FILE_KIND, name)
  }
  /** The FILE sibling a picked name collides with, or none: what a same-name upload replaces. */
  function siblingFile(name: string): TreeNode | undefined {
    const s = sibling(name)
    return s && s.kind === FILE_KIND ? s : undefined
  }
  /** "“a-b” was not uploaded. There is already a folder named “a.b” here." - the sibling named by its kind (core's words). */
  const takenBy = (name: string, other: TreeNode) => t('depot.nameTakenBy', { name, reason: nameTakenMessage(t, other) })

  // A picked file whose name another kind's sibling holds is not sent (inbox #400): the server would refuse it
  // (`409 slug_taken`) and say only "a similar name". Said here, per file, until dismissed or the level changes.
  type Refusal = { key: string; text: string }
  const refusals = ref<Refusal[]>([])
  let refusalSeq = 0
  watch(folderId, () => (refusals.value = []))

  function refresh(): void {
    source.refresh(loadBlobs)
  }

  /**
   * Enqueue one job per file. A file whose name a FILE sibling already holds REPLACES that file's content (owner decision
   * 2026-10-14: the one way to replace, for a member and a contributor guest alike - it reverses inbox #392, where a
   * guest's was refused) where the matrix grants `replace`, and replacing always asks first (inbox #383: the old content
   * cannot be restored, there is no version history) - once per such file; declined -> that file is skipped. A file that
   * holds no content asks nothing (there is nothing to lose, #384), nor does `confirmed` with `replaceNode`: "replace
   * anyway" after a peer's replace, itself the explicit answer. The node keeps its name either way; the blob the visitor
   * saw is what the replace expects to find (`expectedBlobId`). A guest is named (the page's one flow) only after every
   * question, and a declined name sends nothing.
   */
  async function startUploads(files: File[], replaceNode?: TreeNode, confirmed = false): Promise<void> {
    const upload = source.upload
    if (!upload || !upload.ready() || !(replaceNode ? rights.value.replace : rights.value.upload) || files.length === 0) return
    const batch = upload.begin(ctx.value.folder)
    if (!batch) return
    const entries: FileUploadEntry[] = []
    for (const file of files) {
      // A name a folder, Memory or drop box here holds (each folded by its own kind): no question, nothing sent - for a
      // member and a guest alike. A FILE sibling is the replace below.
      const other = replaceNode ? null : sibling(file.name)
      if (other && other.kind !== FILE_KIND) {
        refusals.value = [...refusals.value, { key: `refused-${++refusalSeq}`, text: takenBy(file.name, other) }]
        continue
      }
      const existing = replaceNode ?? (rights.value.replace ? siblingFile(file.name) : undefined)
      if (existing && !confirmed && !isEmpty(existing)) {
        const accepted = await ask({
          header: t('depot.replaceTitle'),
          message: t('depot.replaceConfirm', { name: existing.name }),
          acceptLabel: t('depot.replaceAccept'),
          acceptIcon: 'upload',
        })
        if (!accepted) continue
      }
      const nodeKey = existing ? keyOf(existing) : null
      if (existing && !nodeKey) continue
      entries.push(
        existing && nodeKey
          ? { file, replace: { nodeId: existing.id, nodeKey, nodeEpoch: existing.epoch, expectedBlobId: blobs.value.get(existing.id)?.blobId ?? null } }
          : { file },
      )
    }
    if (entries.length === 0) return
    const jobs = await batch.jobs(entries)
    if (jobs.length === 0) return
    enqueue<FileUploadResult, FileJobExtra>(jobs, {
      label: ctx.value.folder?.name ?? t('depot.title'),
      key: batchKey.value,
      onItemDone: refresh,
      onComplete: refresh, // a failed upload may still have created its node
    })
  }

  const takePicked = (input: HTMLInputElement): File[] => {
    const files = [...(input.files ?? [])]
    input.value = '' // the same file can be picked again
    return files
  }
  // A veyq download among the picked files is core's to recognise (ADR 0082 decision 7): the level offers it as an import,
  // and "Upload as a file" hands it back here. The rest is uploaded at once.
  const screen = (files: File[]): Promise<File[]> =>
    host.screenUpload ? host.screenUpload(files, (back) => void startUploads(back)) : Promise.resolve(files)
  picker.addEventListener('change', () => void screen(takePicked(picker)).then((rest) => startUploads(rest)))

  // "Upload files" is an entry of core's level "New" menu (`register.ts`, inbox #406), and the level hands dropped files to
  // the same entry: both exist for as long as the level does, whichever tab is shown. A drop is only ever as enabled as the
  // menu entry (core asks `enabled()` first).
  host.registerAction(UPLOAD_ACTION, {
    enabled: () => rights.value.upload && writeReady.value,
    run: () => picker.click(),
    drop: (files) => void startUploads(files),
  })

  // --- upload a folder (inbox #424) ---------------------------------------------------------------------------------

  const folderPicker = document.createElement('input')
  folderPicker.type = 'file'
  folderPicker.webkitdirectory = true
  const folderReady = computed(() => !!source.upload && canUploadFolder(ctx.value.access, targetOf(ctx.value.tree, ctx.value.folder)) && writeReady.value)
  const refuse = (text: string) => (refusals.value = [...refusals.value, { key: `refused-${++refusalSeq}`, text }])

  /** Upload `dir` as its tree under the folder on screen (see `uploadFolder.ts`): the preflight, the folders, then one queue
   *  job per file. */
  async function uploadFolder(dir: DirectoryInput): Promise<void> {
    const upload = source.upload
    if (!upload || !folderReady.value || dir.files.length === 0) return
    const level = ctx.value.folder
    const plan = planFolderUpload(dir, (ctx.value.tree as TreeSource<TreeNode>).children(folderId()))
    // Before anything is written: would the tree fit the tenant's quota? Only a member can ask (a guest's link has a limit of
    // its own, which its uploads meet); a "no" says so and writes nothing, an unanswered question never blocks.
    if (!guest && plan.bytes > 0) {
      const tenantId = activeSpace.tenantId
      if (tenantId && (await tenantStorageFits(tenantId, plan.bytes)) === false) {
        refuse(t('depot.uploadFolderNoRoom', { name: plan.root.name, size: formatBytes(plan.bytes, locale.value) }))
        return
      }
    }
    const tree = await upload.beginTree(level)
    if (!tree) return
    let made: Awaited<ReturnType<typeof createFolders>>
    try {
      made = await createFolders(plan, tree)
    } catch {
      refuse(t('depot.uploadFolderFailed', { name: plan.root.name }))
      return
    } finally {
      refresh()
    }
    for (const path of made.failed) refuse(t('depot.uploadFolderPartFailed', { name: path }))
    // Once nothing more can land, a guest takes back the folders that hold nothing (a full link refused every file).
    const takeBack = (items: Parameters<typeof takeBackEmptyFolders>[2]) => void takeBackEmptyFolders(made, tree, items).then(refresh)
    if (made.jobs.length === 0) {
      takeBack([])
      return
    }
    enqueue<FileUploadResult, FileJobExtra>(made.jobs, {
      label: made.rootName,
      key: batchKey.value,
      onItemDone: refresh,
      onComplete: (batch) => {
        refresh()
        takeBack(batch.files)
      },
    })
  }

  // A download unpacked is core's to recognise (ADR 0082 decision 7): for a member it is the import, never this upload.
  const takeDirectory = (dir: DirectoryInput) => {
    if (!host.screenDirectory?.(dir)) void uploadFolder(dir)
  }
  folderPicker.addEventListener('change', () => {
    const dir = pickedDirectory([...(folderPicker.files ?? [])])
    folderPicker.value = '' // the same directory can be picked again
    if (dir) takeDirectory(dir)
  })
  host.registerAction(UPLOAD_FOLDER_ACTION, {
    enabled: () => folderReady.value,
    run: () => folderPicker.click(),
    // core has looked at a dropped directory already (one import per drop): what reaches here is the folder upload
    dropDirectory: (dir) => void uploadFolder(dir),
  })

  // --- what the status menu cannot explain: this level's files that were not (fully) uploaded -----------------------

  const KNOWN_ERRORS = new Set<string>([...Object.values(FileUploadError), QUOTA_EXCEEDED, APPLICATION_OFF])
  /** Why a file was not uploaded, in this session's words: a guest's link has a limit of its own and no trash to point at. */
  function noticeText(code: string, name: string): string {
    if (guest && code === QUOTA_EXCEEDED) return t('depot.guest.linkFull', { name })
    if (code === FileUploadError.slugTaken) {
      // The race backstop: a sibling that came since the pick (or one the pre-check could not see) - named when it is here now.
      const other = sibling(name)
      if (other && other.kind !== FILE_KIND) return takenBy(name, other)
    }
    return t(`depot.errors.${code}`, { name })
  }

  /** After a peer's replace: upload the same file again, expecting the blob the slot holds NOW (re-read first). */
  async function replaceAnyway(item: UploadItem<FileUploadResult, FileJobExtra>) {
    const node = fileNodes.value.find((f) => f.id === item.replaceNodeId)
    removeFile(item.id)
    if (!node) return
    await loadBlobs()
    await startUploads([item.file], node, true)
  }

  const notices = computed<LevelNotice[]>(() => {
    const mine = batches.value.filter((b) => b.key === batchKey.value)
    const madeGood = deliveredSince(mine) // the same file uploaded since: nothing left to explain (inbox #395)
    // No standing notice for a contributor guest (owner 2026-10-14): the share's role badge says what the link allows,
    // and every replace asks before it happens, saying the old content cannot be restored (inbox #383).
    const refused = refusals.value.map((r): LevelNotice => ({
      key: r.key, severity: 'error', text: r.text,
      dismiss: () => (refusals.value = refusals.value.filter((x) => x.key !== r.key)),
    }))
    const failed = mine
      .flatMap((b) => b.files as UploadItem<FileUploadResult, FileJobExtra>[])
      .flatMap((item): LevelNotice[] => {
        if (item.status !== 'failed' || madeGood.has(item.id)) return []
        const code = item.error && KNOWN_ERRORS.has(item.error) ? item.error : 'failed'
        return [{
          key: item.id, severity: 'error', text: noticeText(code, item.name),
          actions: code === FileUploadError.staleFile && rights.value.replace
            ? [{ id: 'replace-anyway', label: t('depot.replaceAnyway'), onClick: () => void replaceAnyway(item) }]
            : [],
          dismiss: () => removeFile(item.id),
        }]
      })
    return [...refused, ...failed]
  })

  // --- download ---------------------------------------------------------------------------------------------

  const downloading = ref<string | null>(null)
  async function download(node: TreeNode, key: Uint8Array | null, blob: FileBlobRef | null) {
    if (!source.scopeId() || !key || !blob || downloading.value) return
    downloading.value = node.id
    try {
      // Nothing awaited before it: the save-file picker needs this click's activation (`openSaveTarget`).
      await source.download(key, node.id, blob, node.name)
    } catch (e) {
      if (e instanceof SaveTooLarge) toast.error(t('depot.downloadTooLarge'))
      else if (!isSaveCancelled(e)) toast.error(t('depot.downloadFailed'))
    } finally {
      downloading.value = null
    }
  }

  // --- the rows core draws ------------------------------------------------------------------------------------------

  function fileRow(node: TreeNode): LevelRow {
    const key = keyOf(node)
    const blob = blobs.value.get(node.id) ?? null
    const name = node.name
    // The matrix for THIS file, as its own page (`FileDetail`) asks it: core's rename, move and delete narrowed by Depot and
    // by the doors this session's source carries (a guest's has no rename and no delete), core's defaults run them.
    const c = sourceRights(depotRights(ctx.value.access, targetOf(ctx.value.tree, node)), source)
    const actions: LevelRowAction[] = [
      // The one first-class action: a file's purpose is to be taken.
      {
        id: 'download', label: t('depot.download', { name }), icon: 'download', first: true, shown: c.download,
        loading: downloading.value === node.id, disabled: !blob || !key || downloading.value !== null,
        onClick: () => void download(node, key, blob),
      },
    ]
    return {
      icon: 'file',
      title: name,
      meta: t('depot.kindFile'),
      details: fileDetails(key, node.id, blob),
      primary: { label: t('depot.detail.open', { name }), onClick: () => host.open?.(node) },
      actions,
      // Delete also where nothing is placed: a guest's identity folder, where a member removes what a guest sent file by
      // file - a content-less one too, a guest's upload that never finished (inbox #391). A selection reads the same answers.
      ...rowRightsOf(c),
    }
  }

  function boxRow(node: TreeNode): LevelRow {
    const name = node.name
    // The matrix for THIS drop box (`move`, `delete` are about the node on screen; the delete door is the source's).
    const c = sourceRights(depotRights(ctx.value.access, targetOf(ctx.value.tree, node)), source)
    const guests = ctx.value.tree.children(node.id).length
    return {
      icon: 'inbox',
      title: name,
      meta: t('depot.dropBox.kind'),
      details: [{ id: 'people', text: t('depot.dropBox.people', guests) }],
      primary: { label: t('depot.dropBox.open', { name }), onClick: () => host.open?.(node) },
      actions: [],
      // Core's move and delete, its sentence naming what goes with it; a drop box is never renamed here (its page neither).
      ...rowRightsOf({ ...c, rename: false }),
    }
  }

  // One computed table per kind, so a render builds each row once (a row's facts open sealed meta).
  const fileRows = computed(() => new Map(fileNodes.value.map((n) => [n.id, fileRow(n)])))
  const boxRows = computed(() => new Map(boxNodes.value.map((n) => [n.id, boxRow(n)])))

  return {
    row: (node) => (node.kind === FILE_KIND ? fileRows.value.get(node.id) : node.kind === DROPBOX_KIND ? boxRows.value.get(node.id) : undefined) ?? null,
    notices,
  }
}
