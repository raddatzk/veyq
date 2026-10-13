// Files' part of the unified level list (inbox #407, ADR 0079): the ROW DATA of a folder level's `FILE` and `FILES_DROPBOX`
// children, and the flows behind their actions. Core draws the list (`core/node/LevelView.vue`, `LevelRowItem.vue`); this
// factory is called once per level view (`register.ts` names it as both kinds' `list.rows`) and returns what core needs per
// node (`LevelRowSource`). It replaces the former `FilesSection.vue` and `DropBoxSection.vue`; what those did beyond drawing
// lives here:
//
// - **The blobs of the level's files** (the source's listing: size, the sealed modified time, "not uploaded yet"), re-read
//   when the folder or the files change and on the level's live tick (inbox #408): a peer's upload completes WITHOUT the
//   tree changing (the node is listed first, its content after), and a peer's replace swaps a blob under an unchanged node.
// - **Upload and replace.** One core-queue job per picked or dropped file (`uploadFile.ts`): the top bar's status menu shows
//   its progress. "Upload files" is a level action (`files.upload`, a "New" menu entry, inbox #406) registered with the level
//   for as long as it is mounted - so it does not depend on which tab is shown - and it takes dropped files too (`drop`,
//   which core calls only while the action is enabled). The row's replace asks first (inbox #383: the old content cannot be
//   restored), once per such file; a file that holds no content asks nothing (#384), nor does "replace anyway" after a
//   peer's replace. A picked name that a sibling of ANOTHER kind holds (a folder, a Memory, a drop box - each folded by its
//   own kind) is not sent at all, for a member and a guest alike: a notice names that sibling instead (inbox #400).
// - **The notices** only this application can explain: why a file of THIS folder was not uploaded, with the one remedy
//   there is (replace anyway after a peer's replace); for a contributor guest, what an upload and a replace do (#383).
// - **Download and delete** (the delete into the scope's trash with the undo, inbox #385 (c)).
//
// What a row offers is Files' capability matrix for the level (`./capabilities.ts`, inbox #401) narrowed to the doors the
// session's source carries (`./source.ts`): a member and a share guest get the same rows, a guest fewer actions. Every action
// is `shown` by that answer - fail closed. A row's primary click OPENS the node (a file's own page `FileDetail`, a drop box's
// `DropBoxDetail`); the file's download is its one first-class action.
//
// Operator-blind: the names, sizes, types and dates shown are opened here under keys the server never sees; Files' calls are
// the listing (ids, sealed sizes and each blob's sealed meta) and the completion (ids only).
import { computed, ref, watch, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LevelNotice, LevelRow, LevelRowAction, LevelRowDetail, LevelRowSource, LevelRowsContext, LevelRowsHost } from '../../core/node/levelRow'
import type { TreeNode } from '../../core/node/tree'
import { collidingSibling, nameTakenMessage } from '../../core/node/siblingCollision'
import { useTrashUndo } from '../../core/node/trashUndo'
import { enqueue, removeFile, useUploadQueue, QUOTA_EXCEEDED, type UploadItem } from '../../core/blob/queue'
import { activeSpace } from '../../app/activeSpace'
import { useConfirm } from '../../shared/state/useConfirm'
import { useToast } from '../../shared/state/useToast'
import { formatBytes } from '../../shared/formatBytes'
import type { FileBlobRef } from '../../api/generated/files'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { deliveredSince, FileUploadError, type FileJobExtra, type FileUploadResult } from './uploadFile'
import { fileFacts } from './download'
import { plaintextTotal } from '../../sw/range'
import { isSaveCancelled, SaveTooLarge } from '../../shared/saveStream'
import { filesCapabilities } from './capabilities'
import { targetOf, type GuestAccess, type TreeSource } from '../../core/access'
import { guestFilesSource, memberFilesSource, sourceCapabilities, type FileUploadEntry, type FilesSource } from './source'

/** The id of Files' "Upload files" level action (`register.ts` declares the menu entry under it). */
export const UPLOAD_ACTION = 'files.upload'

export function filesRows(ctx: Readonly<Ref<LevelRowsContext>>, host: LevelRowsHost): LevelRowSource {
  // One session for the source's life: a member's level or a link's page, never both - so the source is fixed at setup.
  const guest = ctx.value.access.mode === 'guest'
  const source: FilesSource = guest
    ? guestFilesSource(
        (): GuestAccess => {
          const a = ctx.value.access
          if (a.mode !== 'guest') throw new Error('a guest level was handed a member access')
          return a
        },
        { move: (node) => host.move(node) },
      )
    : memberFilesSource({
        scopeId: () => activeSpace.scopeId,
        epochs: () => ctx.value.epochs,
        keyOf: (id) => ctx.value.tree.keyOf(id),
        rename: (node) => host.rename(node),
        move: (node) => host.move(node),
      })

  const { t, locale } = useI18n()
  const { ask } = useConfirm()
  const toast = useToast()
  const offerUndo = useTrashUndo()
  const { batches } = useUploadQueue()

  const folderId = () => ctx.value.folder?.id ?? null
  /** Only this application's nodes, whatever the level hands in. */
  const fileNodes = computed(() => ctx.value.nodes.filter((n) => n.kind === FILE_KIND))
  const boxNodes = computed(() => ctx.value.nodes.filter((n) => n.kind === DROPBOX_KIND))
  /** What the matrix says for the level on screen, narrowed to the doors this session's source carries. */
  const caps = computed(() => sourceCapabilities(filesCapabilities(ctx.value.access, targetOf(ctx.value.tree, ctx.value.folder)), source))
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
    if (!blob) return [{ id: 'state', text: t('files.notUploaded') }]
    const scopeId = source.scopeId()
    const facts = key && scopeId ? fileFacts(scopeId, key, nodeId, blob) : null
    const size = facts?.size ?? Math.max(0, plaintextTotal(blob.byteSize, blob.chunkCount))
    const modifiedAt = facts?.meta?.modifiedAt
    return [
      { id: 'size', text: formatBytes(size, locale.value) },
      // The sealed modified time may be missing (a client that did not write it): then it is simply not shown.
      ...(modifiedAt === undefined ? [] : [{ id: 'modified', text: t('files.modified', { date: dateFormat.value.format(modifiedAt) }) }]),
    ]
  }

  // --- upload and replace -------------------------------------------------------------------------------------------

  // The pickers are built, never drawn: a level has no markup of its own to hold an `<input type="file">`, and a detached
  // input opens the browser's picker on `click()` like an attached one.
  const picker = document.createElement('input')
  picker.type = 'file'
  picker.multiple = true
  const replacePicker = document.createElement('input')
  replacePicker.type = 'file'
  let replaceTarget: TreeNode | null = null

  const batchKey = computed(() => source.batchKey(folderId()))

  /** The sibling of ANY kind a picked name collides with (inbox #400): what the server compares - a file's name folded by
   *  the file's function, each sibling's by its own kind's (core's `collidingSibling`), so `Foto.JPG` meets `foto.jpg` and
   *  a file `a-b` meets a folder `a.b` - or null. Read from the tree, which holds every kind of this level. */
  function sibling(name: string): TreeNode | null {
    const parentId = folderId()
    // Both sessions' trees serve decrypted `TreeNode`s (the guest's `guestTreeSource` too).
    return collidingSibling((ctx.value.tree as TreeSource<TreeNode>).children(parentId), parentId, FILE_KIND, name)
  }
  /** The FILE sibling a picked name collides with, or none: what a member's same-name upload replaces. */
  function siblingFile(name: string): TreeNode | undefined {
    const s = sibling(name)
    return s && s.kind === FILE_KIND ? s : undefined
  }
  /** "“a-b” was not uploaded. There is already a folder named “a.b” here." - the sibling named by its kind (core's words). */
  const takenBy = (name: string, other: TreeNode) => t('files.nameTakenBy', { name, reason: nameTakenMessage(t, other) })

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
   * Enqueue one job per file. Replacing a file's content always asks first (inbox #383: the old content cannot be
   * restored, there is no version history) - once per such file; declined -> that file is skipped: a file whose name a
   * FILE sibling already holds (a member's upload only: a guest's is refused by the server, inbox #392), and
   * `replaceNode`, the row the visitor chose "replace" on, whose picked file goes into it whatever its name (the question
   * names both; a guest's says it changes for everyone, the owner too). A file that holds no content asks nothing (there
   * is nothing to lose, #384), nor does `confirmed`: "replace anyway" after a peer's replace, itself the explicit answer.
   * The node keeps its name either way; the blob the visitor saw is what the replace expects to find (`expectedBlobId`).
   * A guest is named (the page's one flow) only after every question, and a declined name sends nothing.
   */
  async function startUploads(files: File[], replaceNode?: TreeNode, confirmed = false): Promise<void> {
    const upload = source.upload
    if (!upload || !upload.ready() || !(replaceNode ? caps.value.replace : caps.value.upload) || files.length === 0) return
    const batch = upload.begin(ctx.value.folder)
    if (!batch) return
    const entries: FileUploadEntry[] = []
    for (const file of files) {
      // A name a folder, Memory or drop box here holds (each folded by its own kind): no question, nothing sent - for a
      // member and a guest alike. A FILE sibling stays the replace below (a guest's is refused by the server).
      const other = replaceNode ? null : sibling(file.name)
      if (other && other.kind !== FILE_KIND) {
        refusals.value = [...refusals.value, { key: `refused-${++refusalSeq}`, text: takenBy(file.name, other) }]
        continue
      }
      const existing = replaceNode ?? (upload.sameNameReplaces ? siblingFile(file.name) : undefined)
      if (existing && !confirmed && !isEmpty(existing)) {
        const accepted = await ask({
          header: t('files.replaceTitle'),
          message: replaceNode
            ? t(guest ? 'files.guest.replaceConfirm' : 'files.replaceRowConfirm', { name: existing.name, file: file.name })
            : t('files.replaceConfirm', { name: existing.name }),
          acceptLabel: t('files.replaceAccept'),
          acceptIcon: 'pi pi-upload',
        })
        if (!accepted) continue
      }
      const nodeKey = existing ? keyOf(existing) : null
      if (existing && !nodeKey) continue
      entries.push(
        existing && nodeKey
          ? { file, replace: { nodeId: existing.id, nodeKey, expectedBlobId: blobs.value.get(existing.id)?.blobId ?? null } }
          : { file },
      )
    }
    if (entries.length === 0) return
    const jobs = await batch.jobs(entries)
    if (jobs.length === 0) return
    enqueue<FileUploadResult, FileJobExtra>(jobs, {
      label: ctx.value.folder?.name ?? t('files.title'),
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
  picker.addEventListener('change', () => void startUploads(takePicked(picker)))
  replacePicker.addEventListener('change', () => {
    const node = replaceTarget
    replaceTarget = null
    const files = takePicked(replacePicker)
    if (node && files.length > 0) void startUploads([files[0]], node)
  })
  function startReplace(node: TreeNode) {
    replaceTarget = node
    replacePicker.click()
  }

  // "Upload files" is an entry of core's level "New" menu (`register.ts`, inbox #406), and the level hands dropped files to
  // the same entry: both exist for as long as the level does, whichever tab is shown. A drop is only ever as enabled as the
  // menu entry (core asks `enabled()` first).
  host.registerAction(UPLOAD_ACTION, {
    enabled: () => caps.value.upload && writeReady.value,
    run: () => picker.click(),
    drop: (files) => void startUploads(files),
  })

  // --- what the status menu cannot explain: this level's files that were not (fully) uploaded -----------------------

  const KNOWN_ERRORS = new Set<string>([...Object.values(FileUploadError), QUOTA_EXCEEDED])
  /** Why a file was not uploaded, in this session's words: a guest's link has a limit of its own and no trash to point at,
   *  and a guest's upload of a name a file here holds was refused on purpose (inbox #392) - the notice says where the
   *  replace is, not the generic "a similar name". */
  function noticeText(code: string, name: string): string {
    if (guest && code === QUOTA_EXCEEDED) return t('files.guest.linkFull', { name })
    if (code === FileUploadError.slugTaken) {
      // The race backstop: a sibling that came since the pick (or one the pre-check could not see) - named when it is here now.
      const other = sibling(name)
      if (other && other.kind !== FILE_KIND) return takenBy(name, other)
      if (guest && other) return t('files.guest.fileExists', { name })
    }
    return t(`files.errors.${code}`, { name })
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
    // A contributor guest is told what an upload or a replace does before it happens (inbox #383); a replace asks again.
    const hint: LevelNotice[] = guest && caps.value.upload ? [{ key: 'contributor-hint', severity: 'info', text: t('files.guest.contributorHint') }] : []
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
          actions: code === FileUploadError.staleFile && caps.value.replace
            ? [{ id: 'replace-anyway', label: t('files.replaceAnyway'), onClick: () => void replaceAnyway(item) }]
            : [],
          dismiss: () => removeFile(item.id),
        }]
      })
    return [...hint, ...refused, ...failed]
  })

  // --- download, delete ---------------------------------------------------------------------------------------------

  const downloading = ref<string | null>(null)
  async function download(node: TreeNode, key: Uint8Array | null, blob: FileBlobRef | null) {
    if (!source.scopeId() || !key || !blob || downloading.value) return
    downloading.value = node.id
    try {
      // Nothing awaited before it: the save-file picker needs this click's activation (`openSaveTarget`).
      await source.download(key, node.id, blob, node.name)
    } catch (e) {
      if (e instanceof SaveTooLarge) toast.error(t('files.downloadTooLarge'))
      else if (!isSaveCancelled(e)) toast.error(t('files.downloadFailed'))
    } finally {
      downloading.value = null
    }
  }

  /** Delete a file - or a drop box, whose sentence names what goes with it - into the scope's trash, after the question. */
  async function remove(node: TreeNode, copyKey: string, titleKey: string) {
    const deleteFile = source.deleteFile
    if (!deleteFile) return
    const accepted = await ask({
      header: t(titleKey),
      message: t(copyKey, { name: node.name }),
      acceptLabel: t('files.deleteAccept'),
      acceptIcon: 'pi pi-trash',
    })
    if (!accepted) return
    try {
      await deleteFile(node.id)
    } catch {
      toast.error(t('files.deleteFailed'))
      return
    }
    // It went to the trash: said, with an undo that restores it (inbox #385 (c)) - where this session can restore.
    const restore = source.restore
    if (restore) offerUndo(node.name, () => restore(node))
  }

  // --- the rows core draws ------------------------------------------------------------------------------------------

  function fileRow(node: TreeNode): LevelRow {
    const key = keyOf(node)
    const blob = blobs.value.get(node.id) ?? null
    const empty = isEmpty(node)
    const name = node.name
    const c = caps.value
    const actions: LevelRowAction[] = [
      // The one first-class action: a file's purpose is to be taken.
      {
        id: 'download', label: t('files.download', { name }), icon: 'pi pi-download', first: true, shown: c.download,
        loading: downloading.value === node.id, disabled: !blob || !key || downloading.value !== null,
        onClick: () => void download(node, key, blob),
      },
      // Its label says what the icon cannot: the picked file's content goes in, the name stays (inbox #385).
      {
        id: 'replace', label: t(empty ? 'files.uploadInto' : 'files.replace', { name }), icon: empty ? 'pi pi-upload' : 'pi pi-sync',
        shown: c.replace, disabled: !writeReady.value, onClick: () => startReplace(node),
      },
      { id: 'rename', label: t('files.rename', { name }), icon: 'pi pi-pencil', shown: c.rename, onClick: () => source.rename?.(node) },
      { id: 'move', label: t('files.move', { name }), icon: 'pi pi-file-export', shown: c.moveFile, onClick: () => source.move?.(node) },
      // Delete also where nothing is placed: a guest's identity folder, where a member removes what a guest sent file by
      // file - a content-less one too, a guest's upload that never finished (inbox #391).
      {
        id: 'delete', label: t('files.delete', { name }), icon: 'pi pi-trash', severity: 'danger', shown: c.deleteFile,
        onClick: () => void remove(node, 'files.deleteConfirm', 'files.deleteTitle'),
      },
    ]
    return {
      icon: 'pi pi-file',
      title: name,
      meta: t('files.kindFile'),
      details: fileDetails(key, node.id, blob),
      primary: { label: t('files.detail.open', { name }), onClick: () => host.open(node) },
      actions,
    }
  }

  function boxRow(node: TreeNode): LevelRow {
    const name = node.name
    // The matrix for THIS drop box (`deleteNode`, `moveNode` are about the node on screen).
    const c = sourceCapabilities(filesCapabilities(ctx.value.access, targetOf(ctx.value.tree, node)), source)
    const guests = ctx.value.tree.children(node.id).length
    return {
      icon: 'pi pi-inbox',
      title: name,
      meta: t('files.dropBox.kind'),
      details: [{ id: 'people', text: t('files.dropBox.people', guests) }],
      primary: { label: t('files.dropBox.open', { name }), onClick: () => host.open(node) },
      actions: [
        { id: 'move', label: t('files.move', { name }), icon: 'pi pi-file-export', shown: c.moveNode, onClick: () => source.move?.(node) },
        {
          id: 'delete', label: t('files.delete', { name }), icon: 'pi pi-trash', severity: 'danger', shown: c.deleteNode && !!source.deleteFile,
          onClick: () => void remove(node, 'files.dropBox.deleteConfirm', 'files.dropBox.delete'),
        },
      ],
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
