<script setup lang="ts">
// Files' section of the folder level view (cr-files-application F1b): what core's `core/node/LevelView.vue` renders for
// the FILE children of a folder or the scope root (`register.ts` names it as FILE's `section`, order 20: after
// Memories'). A section renders in every folder, with or without files (P4b), so an empty one shows its empty state
// and its upload action.
//
// Per file: its icon, its decrypted name (the tree opened it), its size (exact, from the blob's sealed size) and its
// modified time from the blob's own sealed meta (`wrappedBlobMeta`, `download.ts` `fileFacts`, inbox #397), and the
// actions download, replace, rename (core's `FolderNameDialog`, asked for through the section's `rename` event) and
// delete (into the scope's trash, core's `deleteNode`; restored from core's trash dialog like any node). Uploading
// enqueues one core-queue job per picked or dropped file (`uploadFile.ts`): the top bar's status menu shows its
// progress, and this section shows what only it can explain — why a file of THIS folder was not uploaded, with the
// one remedy there is (replace anyway after a peer's replace). A file that holds no content (an upload that never
// finished, e.g. the tab closed mid-way; a given-up job trashes its own empty node) offers an upload into it instead of
// a replace, and a picked file of its name fills it without the replace question — there is nothing to replace (#384).
// What it offers is Files' capability matrix for the level (`../capabilities.ts`, inbox #401): in a guest's identity
// folder a member downloads and deletes per file (inbox #391), a content-less one ('not uploaded yet', a guest's upload
// that never finished) too, and places nothing; during a pending deletion it only downloads.
//
// **A member and a share guest see this one section (inbox #401 S3).** Core's folder page of a READ or READ_WRITE link
// renders it like the member's level does, handed the guest's `access` and `tree`; every read and write goes through
// Files' data source (`../source.ts`) — the member's (`listFiles`, core's node operations, `treeStore`) or the guest's
// (`listShareFiles`, the link's chunk presign, core's guest create and transport) — and a write shows only where the
// matrix grants it AND the source carries it. A guest gets: the download (every role), and for a contributor the upload
// and a row's replace (not in an identity folder), the replace after a question that says it changes the file for
// everyone, the owner too (inbox #383), the guest named first through the page's one flow (`requestGuest`). A
// contributor's upload of a name a file here holds is NOT a replace — the server refuses it (`409 slug_taken`, owner
// decision 2026-10-08, inbox #392) and the notice points at the row's replace. No rename, no delete for any guest.
// A picked name that a sibling of ANOTHER kind holds (a folder, a Memory, a drop box — each name folded by its own kind)
// is not sent at all, for a member and a guest alike: the section names that sibling instead (inbox #400).
//
// Operator-blind: the names, sizes, types and dates shown are opened here under keys the server never sees; Files' calls
// are the listing (ids, sealed sizes and each blob's sealed meta) and the completion (ids only).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import type { LevelSectionProps, LevelSectionEmits } from '../../../core/applications/registry'
import type { TreeNode } from '../../../core/node/tree'
import { collidingSibling, nameTakenMessage } from '../../../core/node/siblingCollision'
import { useTrashUndo } from '../../../core/node/trashUndo'
import { enqueue, removeFile, useUploadQueue, QUOTA_EXCEEDED, type UploadItem } from '../../../core/blob/queue'
import { activeSpace } from '../../../app/activeSpace'
import { useConfirm } from '../../../shared/state/useConfirm'
import { useToast } from '../../../shared/state/useToast'
import { formatBytes } from '../../../shared/formatBytes'
import type { FileBlobRef } from '../../../api/generated/files'
import { FILE_KIND } from '../kinds'
import { deliveredSince, FileUploadError, type FileJobExtra, type FileUploadResult } from '../uploadFile'
import { fileFacts } from '../download'
import { plaintextTotal } from '../../../sw/range'
import { isSaveCancelled, SaveTooLarge } from '../../../shared/saveStream'
import { filesCapabilities } from '../capabilities'
import { targetOf, type GuestAccess, type TreeSource } from '../../../core/access'
import { guestFilesSource, memberFilesSource, sourceCapabilities, type FileUploadEntry, type FilesSource } from '../source'

const props = defineProps<LevelSectionProps>()
const emit = defineEmits<LevelSectionEmits>()

// One session for the section's life: a member's level or a link's page, never both — so the source is fixed at mount.
const guest = props.access.mode === 'guest'
const source: FilesSource = guest
  ? guestFilesSource((): GuestAccess => {
      const a = props.access
      if (a.mode !== 'guest') throw new Error('a guest section was handed a member access')
      return a
    })
  : memberFilesSource({
      scopeId: () => activeSpace.scopeId,
      epochs: () => props.epochs,
      keyOf: (id) => props.tree.keyOf(id),
      rename: (node) => emit('rename', node),
    })

const { t, locale } = useI18n()
const { ask } = useConfirm()
const toast = useToast()
const offerUndo = useTrashUndo()
const { batches } = useUploadQueue()
// The map stands for the whole level (Memories' pins over the subtree): files step aside there, as the folders do — in
// the mode the level hands in (`viewMode`), which is the map only where a shown section draws one (inbox #404).

// Only FILE nodes, whatever the level hands in: the section speaks for its own kind alone.
const fileNodes = computed(() => props.nodes.filter((n) => n.kind === FILE_KIND))
const caps = computed(() => sourceCapabilities(filesCapabilities(props.access, targetOf(props.tree, props.folder)), source))
/** The session can write now (a member's epoch keys are unlocked). */
const writeReady = computed(() => source.upload?.ready() ?? false)

const keyOf = (node: TreeNode): Uint8Array | null => props.tree.keyOf(node.id)

// --- the blobs of this level's files (the source's listing) -----------------------------------------------------

const blobs = ref(new Map<string, FileBlobRef | null>())
let loads = 0
/** Re-read the blob refs of this level's files. Only when there is a file: an empty level asks the server nothing. A
 *  later load wins over an earlier one still in flight. */
async function loadBlobs(): Promise<void> {
  const mine = ++loads
  if (!source.scopeId() || fileNodes.value.length === 0) {
    blobs.value = new Map()
    return
  }
  try {
    const listed = await source.listBlobs(props.folder?.id ?? null)
    if (mine === loads) blobs.value = listed
  } catch {
    // The rows still show names; size falls back to nothing and download is unavailable until the next load.
    if (mine === loads) blobs.value = new Map()
  }
}
watch(
  () => [source.listKey(), props.folder?.id ?? null, fileNodes.value.map((n) => n.id).join(',')],
  () => void loadBlobs(),
  { immediate: true },
)

// --- the rows --------------------------------------------------------------------------------------------------

const dateFormat = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))

/** Whether the file is KNOWN to hold no content: listed without a blob (not merely unlisted after a failed read). */
const isEmpty = (node: TreeNode): boolean => blobs.value.has(node.id) && blobs.value.get(node.id) === null

/** A row's second line from its BLOB alone (inbox #397): the plaintext size (exact, from the sealed size) and the
 *  modified time sealed in the blob's meta — present or not, the node's own payload is never read for it, so a file
 *  shows its facts whatever its node meta holds, and a replace shows the new blob's at once. No blob: not uploaded. */
function rowDetail(key: Uint8Array | null, nodeId: string, blob: FileBlobRef | null): string {
  if (!blob) return t('files.notUploaded')
  const scopeId = source.scopeId()
  const facts = key && scopeId ? fileFacts(scopeId, key, nodeId, blob) : null
  const size = facts?.size ?? Math.max(0, plaintextTotal(blob.byteSize, blob.chunkCount))
  const modifiedAt = facts?.meta?.modifiedAt
  return [formatBytes(size, locale.value), modifiedAt === undefined ? null : t('files.modified', { date: dateFormat.value.format(modifiedAt) })]
    .filter((s): s is string => s !== null)
    .join(' · ')
}

const rows = computed(() =>
  fileNodes.value.map((node) => {
    const key = keyOf(node)
    const blob = blobs.value.get(node.id) ?? null
    return { node, key, blob, detail: rowDetail(key, node.id, blob), empty: isEmpty(node) }
  }),
)
type Row = (typeof rows.value)[number]

// --- upload and replace ----------------------------------------------------------------------------------------

const picker = ref<HTMLInputElement | null>(null)
const replacePicker = ref<HTMLInputElement | null>(null)
const replaceTarget = ref<TreeNode | null>(null)

const batchKey = computed(() => source.batchKey(props.folder?.id ?? null))

/** The sibling of ANY kind a picked name collides with (inbox #400): what the server compares — a file's name folded by
 *  the file's function, each sibling's by its own kind's (core's `collidingSibling`), so `Foto.JPG` meets `foto.jpg` and
 *  a file `a-b` meets a folder `a.b` — or null. Read from the tree, which holds every kind of this level. */
function sibling(name: string): TreeNode | null {
  const parentId = props.folder?.id ?? null
  // Both sessions' trees serve decrypted `TreeNode`s (the guest's `guestTreeSource` too), as Memories' section reads them.
  return collidingSibling((props.tree as TreeSource<TreeNode>).children(parentId), parentId, FILE_KIND, name)
}
/** The FILE sibling a picked name collides with, or none: what a member's same-name upload replaces. */
function siblingFile(name: string): TreeNode | undefined {
  const s = sibling(name)
  return s && s.kind === FILE_KIND ? s : undefined
}
/** "“a-b” was not uploaded. There is already a folder named “a.b” here." — the sibling named by its kind (core's words). */
const takenBy = (name: string, other: TreeNode) => t('files.nameTakenBy', { name, reason: nameTakenMessage(t, other) })

// A picked file whose name another kind's sibling holds is not sent (inbox #400): the server would refuse it
// (`409 slug_taken`) and say only "a similar name". Said here, per file, until dismissed or the level changes.
type Refusal = { key: string; text: string }
const refusals = ref<Refusal[]>([])
let refusalSeq = 0
watch(() => props.folder?.id ?? null, () => (refusals.value = []))

function refresh(): void {
  source.refresh(loadBlobs)
}

/**
 * Enqueue one job per file. Replacing a file's content always asks first (inbox #383: the old content cannot be
 * restored, there is no version history) — once per such file; declined → that file is skipped: a file whose name a
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
  const batch = upload.begin(props.folder)
  if (!batch) return
  const entries: FileUploadEntry[] = []
  for (const file of files) {
    // A name a folder, Memory or drop box here holds (each folded by its own kind): no question, nothing sent — for a
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
    label: props.folder?.name ?? t('files.title'),
    key: batchKey.value,
    onItemDone: refresh,
    onComplete: refresh, // a failed upload may still have created its node
  })
}

const pickedFiles = (e: Event): File[] => {
  const input = e.target as HTMLInputElement
  const files = [...(input.files ?? [])]
  input.value = '' // the same file can be picked again
  return files
}
function onPicked(e: Event) {
  void startUploads(pickedFiles(e))
}
function startReplace(node: TreeNode) {
  replaceTarget.value = node
  replacePicker.value?.click()
}
function onReplacePicked(e: Event) {
  const node = replaceTarget.value
  replaceTarget.value = null
  const files = pickedFiles(e)
  if (node && files.length > 0) void startUploads([files[0]], node)
}
function onDrop(e: DragEvent) {
  if (caps.value.upload) void startUploads([...(e.dataTransfer?.files ?? [])])
}

// --- what the status menu cannot explain: this level's files that were not (fully) uploaded ----------------------

/** One explanation under the header: a queue item that failed (`item`), or a pick that was never sent (`item` null). */
type Notice = { key: string; item: UploadItem<FileUploadResult, FileJobExtra> | null; severity: 'error'; text: string; replaceAnyway: boolean }
const KNOWN_ERRORS = new Set<string>([...Object.values(FileUploadError), QUOTA_EXCEEDED])
/** Why a file was not uploaded, in this session's words: a guest's link has a limit of its own and no trash to point at,
 *  and a guest's upload of a name a file here holds was refused on purpose (inbox #392) — the notice says where the
 *  replace is, not the generic "a similar name". */
function noticeText(code: string, name: string): string {
  if (guest && code === QUOTA_EXCEEDED) return t('files.guest.linkFull', { name })
  if (code === FileUploadError.slugTaken) {
    // The race backstop: a sibling that came since the pick (or one the pre-check could not see) — named when it is here now.
    const other = sibling(name)
    if (other && other.kind !== FILE_KIND) return takenBy(name, other)
    if (guest && other) return t('files.guest.fileExists', { name })
  }
  return t(`files.errors.${code}`, { name })
}
const notices = computed<Notice[]>(() => {
  const mine = batches.value.filter((b) => b.key === batchKey.value)
  const madeGood = deliveredSince(mine) // the same file uploaded since: nothing left to explain (inbox #395)
  const refused = refusals.value.map((r): Notice => ({ key: r.key, item: null, severity: 'error', text: r.text, replaceAnyway: false }))
  return refused.concat(mine
    .flatMap((b) => b.files as UploadItem<FileUploadResult, FileJobExtra>[])
    .flatMap((item): Notice[] => {
      if (item.status === 'failed' && madeGood.has(item.id)) return []
      if (item.status === 'failed') {
        const code = item.error && KNOWN_ERRORS.has(item.error) ? item.error : 'failed'
        return [{ key: item.id, item, severity: 'error', text: noticeText(code, item.name), replaceAnyway: code === FileUploadError.staleFile }]
      }
      return []
    }))
})
function dismiss(n: Notice) {
  if (n.item) removeFile(n.item.id)
  else refusals.value = refusals.value.filter((r) => r.key !== n.key)
}

/** After a peer's replace: upload the same file again, expecting the blob the slot holds NOW (re-read first). */
async function replaceAnyway(n: Notice) {
  const item = n.item
  if (!item) return
  const node = fileNodes.value.find((f) => f.id === item.replaceNodeId)
  removeFile(item.id)
  if (!node) return
  await loadBlobs()
  await startUploads([item.file], node, true)
}

// --- download, rename, delete ----------------------------------------------------------------------------------

const downloading = ref<string | null>(null)
async function download(row: Row) {
  if (!source.scopeId() || !row.key || !row.blob || downloading.value) return
  downloading.value = row.node.id
  try {
    // Nothing awaited before it: the save-file picker needs this click's activation (`openSaveTarget`).
    await source.download(row.key, row.node.id, row.blob, row.node.name)
  } catch (e) {
    if (e instanceof SaveTooLarge) toast.error(t('files.downloadTooLarge'))
    else if (!isSaveCancelled(e)) toast.error(t('files.downloadFailed'))
  } finally {
    downloading.value = null
  }
}

async function remove(node: TreeNode) {
  const deleteFile = source.deleteFile
  if (!deleteFile || !caps.value.deleteFile) return
  const accepted = await ask({
    header: t('files.deleteTitle'),
    message: t('files.deleteConfirm', { name: node.name }),
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
  // It went to the trash: said, with an undo that restores it (inbox #385 (c)) — where this session can restore.
  const restore = source.restore
  if (restore) offerUndo(node.name, () => restore(node))
}
</script>

<template>
  <section v-if="viewMode !== 'map'" class="veyq-section files-section" @dragover.prevent @drop.prevent="onDrop">
    <div class="veyq-section-header">
      <h2>{{ t('files.title') }}</h2>
      <span>{{ fileNodes.length }}</span>
      <div v-if="caps.upload" class="files-toolbar">
        <Button
          type="button"
          :label="t('files.upload')"
          icon="pi pi-upload"
          severity="secondary"
          outlined
          size="small"
          :disabled="!writeReady"
          @click="picker?.click()"
        />
      </div>
    </div>
    <!-- A contributor guest is told what an upload or a replace does before it happens (inbox #383); a replace asks
         again when it is picked. -->
    <p v-if="guest && caps.upload" class="veyq-form-copy">{{ t('files.guest.contributorHint') }}</p>
    <input v-if="caps.upload" ref="picker" class="files-picker" type="file" multiple hidden @change="onPicked" />
    <input v-if="caps.replace" ref="replacePicker" class="files-picker" type="file" hidden @change="onReplacePicked" />

    <Message v-for="n in notices" :key="n.key" :severity="n.severity" :closable="false" class="files-notice">
      <span class="files-notice-body">
        <span>{{ n.text }}</span>
        <span class="veyq-action-group">
          <Button
            v-if="n.replaceAnyway && caps.replace"
            type="button"
            :label="t('files.replaceAnyway')"
            size="small"
            severity="secondary"
            outlined
            @click="replaceAnyway(n)"
          />
          <Button type="button" :label="t('files.dismiss')" size="small" severity="secondary" text @click="dismiss(n)" />
        </span>
      </span>
    </Message>

    <p v-if="!fileNodes.length" class="veyq-empty veyq-empty-inline">{{ guest && !caps.upload ? t('files.guest.empty') : t('files.empty') }}</p>
    <ul v-else class="veyq-list files-list">
      <li v-for="row in rows" :key="row.node.id" class="veyq-list-row files-row">
        <button
          type="button"
          class="files-row-open"
          :aria-label="t('files.download', { name: row.node.name })"
          :disabled="!row.blob || !row.key || downloading !== null"
          @click="download(row)"
        >
          <span class="pi pi-file" aria-hidden="true" />
          <span class="veyq-row-main">
            <span class="veyq-row-title">{{ row.node.name }}</span>
            <span class="veyq-row-subtitle">{{ row.detail }}</span>
          </span>
        </button>
        <span class="veyq-action-group veyq-action-group-icons">
          <Button
            type="button"
            class="veyq-icon-button veyq-prime-icon-button"
            icon="pi pi-download"
            :aria-label="t('files.download', { name: row.node.name })"
            severity="secondary"
            outlined
            size="small"
            :loading="downloading === row.node.id"
            :disabled="!row.blob || !row.key || downloading !== null"
            @click="download(row)"
          />
          <!-- Its tooltip says what the icon cannot: the picked file's content goes in, the name stays (inbox #385). -->
          <Button
            v-if="caps.replace"
            v-tooltip.bottom="t(row.empty ? 'files.uploadInto' : 'files.replace', { name: row.node.name })"
            type="button"
            class="veyq-icon-button veyq-prime-icon-button"
            :icon="row.empty ? 'pi pi-upload' : 'pi pi-sync'"
            :aria-label="t(row.empty ? 'files.uploadInto' : 'files.replace', { name: row.node.name })"
            severity="secondary"
            outlined
            size="small"
            :disabled="!writeReady"
            @click="startReplace(row.node)"
          />
          <Button
            v-if="caps.rename"
            type="button"
            class="veyq-icon-button veyq-prime-icon-button"
            icon="pi pi-pencil"
            :aria-label="t('files.rename', { name: row.node.name })"
            severity="secondary"
            outlined
            size="small"
            @click="source.rename?.(row.node)"
          />
          <!-- Delete also where nothing is placed: a guest's identity folder, where a member removes what a guest sent file
               by file — a content-less one too, a guest's upload that never finished (inbox #391). -->
          <Button
            v-if="caps.deleteFile"
            type="button"
            class="veyq-icon-button veyq-prime-icon-button"
            icon="pi pi-trash"
            :aria-label="t('files.delete', { name: row.node.name })"
            severity="danger"
            outlined
            size="small"
            @click="remove(row.node)"
          />
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.files-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  margin-left: auto;
}
.files-row-open {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.files-row-open:disabled {
  cursor: default;
}
.files-row-open:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
  border-radius: var(--app-radius-sm);
}
.files-notice-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  width: 100%;
}
</style>
