<script setup lang="ts">
// An opened file (inbox #378): the page of ONE file — its name under core's header card (share, rename, move, delete,
// each where Files' matrix says so), its size, type and modified time opened from the blob's own sealed meta, a
// download, and a replace of its content. It is FILE's `detail` in the registry, so the host mounts it for a member who
// opens a file from the folder's list, and core's page of a public link (`ShareTreePage`) mounts the very same component
// for a guest at the link's root — a single-file link — or at a file inside a folder link (inbox #401: a share is the
// member's view with functions removed, never a page of its own).
//
// **What it offers is `filesCapabilities(access, targetOf(tree, file))` narrowed to what the data source carries**
// (`../capabilities.ts`, `../source.ts`): a member everything its row offers plus share, rename, move and delete of the
// file; a `READ` guest the download; a `READ_WRITE` guest the download and the replace — after the question that says it
// changes the file for everyone who sees it, the owner too (inbox #383) — and, for a file inside a folder link, the move
// between the subtree's folders. A guest renames and deletes nothing (nodes store no authorship), and a `WRITE_ONLY`
// link on a file does not exist (the backend refuses it).
//
// The size, type and date are the BLOB's (`download.ts` `fileFacts`): the one listing the source offers
// (`listBlobs` — a member's reads the file's folder, a guest's the link) says which blob the file holds, and the bytes
// download through the session's own door. The replace is a compare-and-set against the blob this page read
// (`expectedBlobId`): a peer's replace in the meantime refuses it (`stale_file`) and the page offers "replace anyway".
//
// **The file is shown when its page opens** (inbox #412, `FileViewer`): an image, a text, a video or audio through the service
// worker's media route, the download offered for anything else; Files makes no thumbnail or preview blob. It reads the blob
// through the source's `chunks` (a member's scope READ gate, a guest's link), so a guest of either role sees the member's
// viewer; nothing about it is a capability of its own beyond the download it offers where the matrix grants one.
//
// Operator-blind as the section: every name, size and date is opened here under keys the tree holds.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import type { NodeDetailEmits, NodeDetailProps } from '../../../core/applications/registry'
import NodeHeader from '../../../core/node/components/NodeHeader.vue'
import { standardNodeActions } from '../../../core/node/nodeHeader'
import { favoriteHeaderActions } from '../../../core/favorites/favoriteActions'
import { enqueue, removeFile, useUploadQueue, QUOTA_EXCEEDED, type UploadItem } from '../../../core/blob/queue'
import { useConfirm } from '../../../shared/state/useConfirm'
import { useToast } from '../../../shared/state/useToast'
import { formatBytes } from '../../../shared/formatBytes'
import { isSaveCancelled, SaveTooLarge } from '../../../shared/saveStream'
import type { FileBlobRef } from '../../../api/generated/files'
import { targetOf, type GuestAccess } from '../../../core/access'
import { filesCapabilities } from '../capabilities'
import { guestFilesSource, memberFilesSource, sourceCapabilities, type FilesSource } from '../source'
import { deliveredSince, FileUploadError, type FileJobExtra, type FileUploadResult } from '../uploadFile'
import { fileFacts } from '../download'
import { extensionOf } from '../../../core/blob/sniff'
import type { ViewerTarget } from '../useFileViewer'
import FileViewer from './FileViewer.vue'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()
const { t, locale } = useI18n()
// Core's heart (inbox #409): the same for every kind; a guest gets none.
const coreActions = computed(() => favoriteHeaderActions(t, props.access, props.node))
const { ask } = useConfirm()
const toast = useToast()
const { batches } = useUploadQueue()

// One session for the view's life (see `../levelRows.ts`): the member's source or the link's, fixed at mount.
const guest = props.access.mode === 'guest'
const source: FilesSource = guest
  ? guestFilesSource(
      (): GuestAccess => {
        const a = props.access
        if (a.mode !== 'guest') throw new Error('a guest view was handed a member access')
        return a
      },
      { move: (node) => emit('move', node) },
    )
  : memberFilesSource({
      scopeId: () => props.scopeId,
      epochs: () => props.epochs,
      keyOf: (id) => props.tree.keyOf(id),
      rename: () => emit('rename'),
      move: (node) => emit('move', node),
    })
const caps = computed(() => sourceCapabilities(filesCapabilities(props.access, targetOf(props.tree, props.node)), source))
/** The header's actions: share, rename, move, delete — each only where the capability matrix grants it. */
const headerActions = computed(() =>
  standardNodeActions(
    t,
    { share: caps.value.share, rename: caps.value.rename, move: caps.value.moveNode, remove: caps.value.deleteNode },
    { share: () => emit('share'), rename: () => emit('rename'), move: () => source.move?.(props.node), remove: () => emit('delete') },
  ),
)
const key = computed(() => props.contentKey ?? props.tree.keyOf(props.node.id))
const writeReady = computed(() => source.upload?.ready() ?? false)

// --- the file's blob -------------------------------------------------------------------------------------------------

/** `undefined`: not read yet (or the read failed); `null`: the file holds no content; else its blob. */
const blob = ref<FileBlobRef | null | undefined>(undefined)
let loads = 0
async function loadBlob(): Promise<void> {
  const mine = ++loads
  if (!source.scopeId()) return
  try {
    const listed = await source.listBlobs(props.node.parentId ?? null)
    if (mine === loads) blob.value = listed.get(props.node.id) ?? null
  } catch {
    if (mine === loads) blob.value = undefined // download stays unavailable until the next read
  }
}
watch(() => [source.listKey(), props.node.id], () => void loadBlob(), { immediate: true })

const dateFormat = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))
/** The facts of the blob, opened under the file's key (a blob that carries no meta, or whose meta does not open, shows its size alone). */
const facts = computed(() => {
  const scopeId = source.scopeId()
  const b = blob.value
  if (!b) return null
  const opened = key.value && scopeId ? fileFacts(scopeId, key.value, props.node.id, b) : null
  return { size: opened?.size ?? 0, type: opened?.meta?.type || null, modifiedAt: opened?.meta?.modifiedAt ?? null, uploadedName: opened?.meta?.name ?? null }
})
const empty = computed(() => blob.value === null)

/** What the viewer opens: this blob, read through the session's own door. The claims (sealed type; the node's name, else the
 *  uploaded one when the node's has no extension) only narrow what the decrypted bytes already allow (`view.ts`). Null until
 *  the blob is read, or when its ref is not this node's (`OpenRefused`: nothing is shown, as the facts show none). */
const viewTarget = computed<ViewerTarget | null>(() => {
  const b = blob.value
  const k = key.value
  if (!b || !k || !source.scopeId()) return null
  try {
    const name = extensionOf(props.node.name) ? props.node.name : (facts.value?.uploadedName ?? props.node.name)
    return { id: b.blobId, source: source.chunks(k, props.node.id, b), hints: { type: facts.value?.type, name } }
  } catch {
    return null
  }
})

// --- download --------------------------------------------------------------------------------------------------------

const downloading = ref(false)
async function download() {
  const b = blob.value
  const k = key.value
  if (!b || !k || downloading.value || !source.scopeId()) return
  downloading.value = true
  try {
    // Nothing awaited before it: the save-file picker needs this click's activation (`openSaveTarget`).
    await source.download(k, props.node.id, b, props.node.name)
  } catch (e) {
    if (e instanceof SaveTooLarge) toast.error(t('files.downloadTooLarge'))
    else if (!isSaveCancelled(e)) toast.error(t('files.downloadFailed'))
  } finally {
    downloading.value = false
  }
}

// --- replace ---------------------------------------------------------------------------------------------------------

const picker = ref<HTMLInputElement | null>(null)
const batchKey = computed(() => source.batchKey(props.node.id))

function refresh(): void {
  source.refresh(loadBlob)
}

/** Replace the content with `file`: it asks first unless the file holds nothing yet (inbox #383, #384) or `confirmed`
 *  ("replace anyway" after a peer's replace is itself the answer); the node keeps its name, and the blob this page read is
 *  what the replace expects to find. A guest is named (the page's one flow) only after the question. */
async function startReplace(file: File, confirmed = false): Promise<void> {
  const upload = source.upload
  const nodeKey = key.value
  if (!upload || !upload.ready() || !caps.value.replace || !nodeKey) return
  const batch = upload.beginReplace()
  if (!batch) return
  if (!confirmed && !empty.value) {
    const accepted = await ask({
      header: t('files.replaceTitle'),
      message: t(guest ? 'files.guest.replaceFileConfirm' : 'files.replaceRowConfirm', { name: props.node.name, file: file.name }),
      acceptLabel: t('files.replaceAccept'),
      acceptIcon: 'pi pi-upload',
    })
    if (!accepted) return
  }
  const jobs = await batch.jobs([{ file, replace: { nodeId: props.node.id, nodeKey, expectedBlobId: blob.value?.blobId ?? null } }])
  if (jobs.length === 0) return
  enqueue<FileUploadResult, FileJobExtra>(jobs, {
    label: props.node.name,
    key: batchKey.value,
    onItemDone: refresh,
    onComplete: refresh,
  })
}
function onPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // the same file can be picked again
  if (file) void startReplace(file)
}

// --- what the status menu cannot explain: why this file's replace did not go through ------------------------------------

type Notice = { key: string; item: UploadItem<FileUploadResult, FileJobExtra>; text: string; replaceAnyway: boolean }
const KNOWN_ERRORS = new Set<string>([...Object.values(FileUploadError), QUOTA_EXCEEDED])
function noticeText(code: string, name: string): string {
  if (guest && code === QUOTA_EXCEEDED) return t('files.guest.linkFull', { name })
  return t(`files.errors.${code}`, { name })
}
const notices = computed<Notice[]>(() => {
  const mine = batches.value.filter((b) => b.key === batchKey.value)
  const madeGood = deliveredSince(mine)
  return mine
    .flatMap((b) => b.files as UploadItem<FileUploadResult, FileJobExtra>[])
    .filter((item) => item.status === 'failed' && !madeGood.has(item.id))
    .map((item) => {
      const code = item.error && KNOWN_ERRORS.has(item.error) ? item.error : 'failed'
      return { key: item.id, item, text: noticeText(code, item.name), replaceAnyway: code === FileUploadError.staleFile }
    })
})
/** After a peer's replace: the same file again, expecting the blob the slot holds NOW (re-read first). */
async function replaceAnyway(n: Notice) {
  const file = n.item.file
  removeFile(n.item.id)
  await loadBlob()
  await startReplace(file, true)
}
</script>

<template>
  <!-- Opens like a folder (inbox #385 (b)): core's `NodeHeader` with the file's own actions, then what it holds. -->
  <div class="veyq-level-view">
    <NodeHeader icon="pi pi-file" :title="node.name" :level="1" :actions="headerActions" :core-actions="coreActions" />
    <section class="veyq-section file-detail">
      <Message v-for="n in notices" :key="n.key" severity="error" :closable="false" class="files-notice">
        <span class="file-detail-notice">
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
            <Button type="button" :label="t('files.dismiss')" size="small" severity="secondary" text @click="removeFile(n.item.id)" />
          </span>
        </span>
      </Message>

      <!-- The file itself, above its facts (inbox #412). -->
      <FileViewer v-if="!empty" :target="viewTarget" :name="node.name" :can-download="caps.download" @download="download" />
      <p v-if="empty" class="veyq-empty veyq-empty-inline">{{ t('files.notUploaded') }}</p>
      <dl v-else-if="facts" class="file-detail-facts">
        <dt>{{ t('files.detail.size') }}</dt>
        <dd>{{ formatBytes(facts.size, locale) }}</dd>
        <dt>{{ t('files.detail.type') }}</dt>
        <dd>{{ facts.type ?? t('files.detail.typeUnknown') }}</dd>
        <template v-if="facts.modifiedAt !== null">
          <dt>{{ t('files.detail.modified') }}</dt>
          <dd>{{ dateFormat.format(facts.modifiedAt) }}</dd>
        </template>
      </dl>

      <!-- A contributor is told what a replace does before it happens (inbox #383); the question asks again when a file is picked. -->
      <p v-if="guest && caps.replace" class="veyq-form-copy">{{ t('files.detail.contributorHint') }}</p>

      <div class="veyq-action-group file-detail-actions">
        <Button
          v-if="caps.download"
          type="button"
          class="file-detail-download"
          icon="pi pi-download"
          :label="t('files.detail.download')"
          :aria-label="t('files.download', { name: node.name })"
          :loading="downloading"
          :disabled="!blob || !key || downloading"
          @click="download"
        />
        <Button
          v-if="caps.replace"
          type="button"
          class="file-detail-replace"
          :icon="empty ? 'pi pi-upload' : 'pi pi-sync'"
          :label="t(empty ? 'files.detail.uploadInto' : 'files.detail.replace')"
          :aria-label="t(empty ? 'files.uploadInto' : 'files.replace', { name: node.name })"
          severity="secondary"
          outlined
          :disabled="!writeReady"
          @click="picker?.click()"
        />
        <input v-if="caps.replace" ref="picker" class="files-picker" type="file" hidden @change="onPicked" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.file-detail-facts {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.35rem 1rem;
  margin: 0 0 1rem;
}
.file-detail-facts dt {
  color: var(--app-muted);
}
.file-detail-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.file-detail-actions {
  flex-wrap: wrap;
}
.file-detail-notice {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  width: 100%;
}
</style>
