<script setup lang="ts">
// Files' section of the folder level view (cr-files-application F1b): what core's `core/node/LevelView.vue` renders for
// the FILE children of a folder or the scope root (`register.ts` names it as FILE's `section`, order 20: after
// Memories'). A section renders in every folder, with or without files (P4b), so an empty one shows its empty state
// and its upload action.
//
// Per file: its icon, its decrypted name (the tree opened it), its size and modified time from the file's sealed
// metadata (`fileMeta.ts`; before a meta is written, the plaintext size the blob's sealed size gives exactly), and the
// actions download, replace, rename (core's `FolderNameDialog`, asked for through the section's `rename` event) and
// delete (into the scope's trash, core's `deleteNode`; restored from core's trash dialog like any node). Uploading
// enqueues one core-queue job per picked or dropped file (`uploadFile.ts`): the top bar's status menu shows its
// progress, and this section shows what only it can explain — why a file of THIS folder was not uploaded, with the
// one remedy there is (replace anyway after a peer's replace).
//
// Operator-blind: the names, sizes, types and dates shown are opened here under keys the server never sees; the only
// call of Files' own is `listFiles`, which returns ids and sealed sizes.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import * as v1 from '@veyq/crypto/v1'
import type { LevelSectionProps, LevelSectionEmits } from '../../../core/applications/registry'
import { registry } from '../../../core/applications/registry'
import { deleteNode, nodeContentKey, type NodeParent, type TreeNode } from '../../../core/node/tree'
import { treeStore, loadTree } from '../../../core/node/treeStore'
import { enqueue, removeFile, useUploadQueue, QUOTA_EXCEEDED, type UploadItem } from '../../../core/blob/queue'
import { activeSpace } from '../../../app/activeSpace'
import { withRefresh } from '../../../app/sessionRefresh'
import { useConfirm } from '../../../shared/state/useConfirm'
import { useToast } from '../../../shared/state/useToast'
import { formatBytes } from '../../../shared/formatBytes'
import { useViewMode } from '../../../shared/state/useViewMode'
import type { FileBlobRef } from '../../../api/generated/files'
import { filesApi } from '../api'
import { FILE_KIND } from '../kinds'
import { readFileMeta } from '../fileMeta'
import { fileUploadJob, FileUploadError, type FileJobExtra, type FileUploadResult } from '../uploadFile'
import { downloadFile } from '../download'
import { isSaveCancelled, SaveTooLarge } from '../../../shared/saveStream'

const props = defineProps<LevelSectionProps>()
const emit = defineEmits<LevelSectionEmits>()

const { t, locale } = useI18n()
const { ask } = useConfirm()
const toast = useToast()
const { batches } = useUploadQueue()
// The map stands for the whole level (Memories' pins over the subtree): files step aside there, as the folders do.
const { viewMode } = useViewMode()

// Only FILE nodes, whatever the level hands in: the section speaks for its own kind alone.
const fileNodes = computed(() => props.nodes.filter((n) => n.kind === FILE_KIND))

const keyOf = (node: TreeNode): Uint8Array | null =>
  props.epochs && activeSpace.scopeId ? nodeContentKey(activeSpace.scopeId, props.epochs, treeStore.nodes, node.id) : null

// --- the blobs of this level's files (`listFiles`) -------------------------------------------------------------

const blobs = ref(new Map<string, FileBlobRef | null>())
let loads = 0
/** Re-read the blob refs of this level's files. Only when there is a file: an empty level asks the server nothing. A
 *  later load wins over an earlier one still in flight. */
async function loadBlobs(): Promise<void> {
  const mine = ++loads
  const scopeId = activeSpace.scopeId
  if (!scopeId || fileNodes.value.length === 0) {
    blobs.value = new Map()
    return
  }
  try {
    const page = await withRefresh(() => filesApi().listFiles({ scopeId, parentId: props.folder?.id }))
    if (mine === loads) blobs.value = new Map(page.items.map((i) => [i.nodeId, i.fileBlob ?? null]))
  } catch {
    // The rows still show names; size falls back to nothing and download is unavailable until the next load.
    if (mine === loads) blobs.value = new Map()
  }
}
watch(
  () => [activeSpace.scopeId, props.folder?.id ?? null, fileNodes.value.map((n) => n.id).join(',')],
  () => void loadBlobs(),
  { immediate: true },
)

// --- the rows --------------------------------------------------------------------------------------------------

const dateFormat = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))

const rows = computed(() =>
  fileNodes.value.map((node) => {
    const key = keyOf(node)
    const meta = key && activeSpace.scopeId ? readFileMeta(key, { scopeId: activeSpace.scopeId, nodeId: node.id }, node.wrappedMeta) : null
    const blob = blobs.value.get(node.id) ?? null
    // Without a meta yet, the blob's sealed size gives the plaintext size exactly: every frame adds FRAME_OVERHEAD.
    const size = meta?.size ?? (blob ? Math.max(0, blob.byteSize - v1.FRAME_OVERHEAD * blob.chunkCount) : null)
    const detail = blob
      ? [size === null ? null : formatBytes(size, locale.value), meta ? t('files.modified', { date: dateFormat.value.format(meta.modifiedAt) }) : null]
          .filter((s): s is string => s !== null)
          .join(' · ')
      : t('files.notUploaded')
    return { node, key, blob, detail }
  }),
)
type Row = (typeof rows.value)[number]

// --- upload and replace ----------------------------------------------------------------------------------------

const picker = ref<HTMLInputElement | null>(null)
const replacePicker = ref<HTMLInputElement | null>(null)
const replaceTarget = ref<TreeNode | null>(null)

const batchKey = computed(() => `files:${activeSpace.scopeId ?? ''}:${props.folder?.id ?? ''}`)

/** Where a new file of this level is created: under the folder with ITS key (ADR 0080 Decision 1), or the scope root.
 *  `undefined` when the folder's key does not open — nothing is created then, rather than a node nobody can read. */
function newFileParent(): NodeParent | undefined {
  if (!props.folder) return null
  const key = keyOf(props.folder)
  return key ? { id: props.folder.id, key } : undefined
}

/** The FILE sibling a picked name collides with: the same name folded by the kind's own function (`fileNameSlug`
 *  through the registry, so `Foto.JPG` meets `foto.jpg`), or none. */
function siblingFile(name: string): TreeNode | undefined {
  const slug = registry.slugFor(FILE_KIND, name)
  return fileNodes.value.find((n) => registry.slugFor(FILE_KIND, n.name) === slug)
}

function refresh(): void {
  void loadTree().then(loadBlobs)
}

/**
 * Enqueue one job per file. A file whose name a FILE sibling already holds replaces that file's content — after the
 * member said so (`ask`, once per such file; declined → that file is skipped). `replaceNode`: the member chose
 * "replace" on that row, so the picked file goes into it whatever its name, without a second question. The node keeps
 * its name either way; the blob the member saw is what the replace expects to find (`expectedBlobId`).
 */
async function startUploads(files: File[], replaceNode?: TreeNode): Promise<void> {
  const scopeId = activeSpace.scopeId
  const epochs = props.epochs
  if (!scopeId || !epochs || props.readonly || files.length === 0) return
  const parent = newFileParent()
  if (parent === undefined) return
  const jobs = []
  for (const file of files) {
    const existing = replaceNode ?? siblingFile(file.name)
    if (existing && !replaceNode) {
      const accepted = await ask({
        header: t('files.replaceTitle'),
        message: t('files.replaceConfirm', { name: existing.name }),
        acceptLabel: t('files.replaceAccept'),
        acceptIcon: 'pi pi-upload',
      })
      if (!accepted) continue
    }
    const nodeKey = existing ? keyOf(existing) : null
    if (existing && !nodeKey) continue
    jobs.push(
      existing && nodeKey
        ? fileUploadJob({
            scopeId, epochs, parent,
            replace: {
              nodeId: existing.id, nodeKey,
              meta: { wrappedMeta: existing.wrappedMeta, metaVersion: existing.metaVersion },
              expectedBlobId: blobs.value.get(existing.id)?.blobId ?? null,
            },
          }, file)
        : fileUploadJob({ scopeId, epochs, parent }, file),
    )
  }
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
  if (!props.readonly) void startUploads([...(e.dataTransfer?.files ?? [])])
}

// --- what the status menu cannot explain: this level's files that were not (fully) uploaded ----------------------

type Notice = { item: UploadItem<FileUploadResult, FileJobExtra>; severity: 'error' | 'warn'; text: string; replaceAnyway: boolean }
const KNOWN_ERRORS = new Set<string>([...Object.values(FileUploadError), QUOTA_EXCEEDED])
const notices = computed<Notice[]>(() =>
  batches.value
    .filter((b) => b.key === batchKey.value)
    .flatMap((b) => b.files as UploadItem<FileUploadResult, FileJobExtra>[])
    .flatMap((item): Notice[] => {
      if (item.status === 'failed') {
        const code = item.error && KNOWN_ERRORS.has(item.error) ? item.error : 'failed'
        return [{ item, severity: 'error', text: t(`files.errors.${code}`, { name: item.name }), replaceAnyway: code === FileUploadError.staleFile }]
      }
      if (item.status === 'done' && item.result && !item.result.metaWritten) {
        return [{ item, severity: 'warn', text: t('files.metaWarning', { name: item.name }), replaceAnyway: false }]
      }
      return []
    }),
)

/** After a peer's replace: upload the same file again, expecting the blob the slot holds NOW (re-read first). */
async function replaceAnyway(n: Notice) {
  const node = fileNodes.value.find((f) => f.id === n.item.replaceNodeId)
  removeFile(n.item.id)
  if (!node) return
  await loadBlobs()
  await startUploads([n.item.file], node)
}

// --- download, rename, delete ----------------------------------------------------------------------------------

const downloading = ref<string | null>(null)
async function download(row: Row) {
  const scopeId = activeSpace.scopeId
  if (!scopeId || !row.key || !row.blob || downloading.value) return
  downloading.value = row.node.id
  try {
    // Nothing awaited before it: the save-file picker needs this click's activation (`openSaveTarget`).
    await downloadFile(scopeId, row.key, row.node.id, row.blob, row.node.name)
  } catch (e) {
    if (e instanceof SaveTooLarge) toast.error(t('files.downloadTooLarge'))
    else if (!isSaveCancelled(e)) toast.error(t('files.downloadFailed'))
  } finally {
    downloading.value = null
  }
}

async function remove(node: TreeNode) {
  const scopeId = activeSpace.scopeId
  if (!scopeId) return
  const accepted = await ask({
    header: t('files.deleteTitle'),
    message: t('files.deleteConfirm', { name: node.name }),
    acceptLabel: t('files.deleteAccept'),
    acceptIcon: 'pi pi-trash',
  })
  if (!accepted) return
  try {
    await deleteNode(scopeId, node.id)
    await loadTree()
  } catch {
    toast.error(t('files.deleteFailed'))
  }
}
</script>

<template>
  <section v-if="viewMode !== 'map'" class="veyq-section files-section" @dragover.prevent @drop.prevent="onDrop">
    <div class="veyq-section-header">
      <h2>{{ t('files.title') }}</h2>
      <span>{{ fileNodes.length }}</span>
      <div v-if="!readonly" class="files-toolbar">
        <Button
          type="button"
          :label="t('files.upload')"
          icon="pi pi-upload"
          severity="secondary"
          outlined
          size="small"
          :disabled="!epochs"
          @click="picker?.click()"
        />
      </div>
    </div>
    <template v-if="!readonly">
      <input ref="picker" class="files-picker" type="file" multiple hidden @change="onPicked" />
      <input ref="replacePicker" class="files-picker" type="file" hidden @change="onReplacePicked" />
    </template>

    <Message v-for="n in notices" :key="n.item.id" :severity="n.severity" :closable="false" class="files-notice">
      <span class="files-notice-body">
        <span>{{ n.text }}</span>
        <span class="veyq-action-group">
          <Button
            v-if="n.replaceAnyway && !readonly"
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

    <p v-if="!fileNodes.length" class="veyq-empty veyq-empty-inline">{{ t('files.empty') }}</p>
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
        <span class="veyq-action-group">
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
          <template v-if="!readonly">
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-sync"
              :aria-label="t('files.replace', { name: row.node.name })"
              severity="secondary"
              outlined
              size="small"
              :disabled="!epochs"
              @click="startReplace(row.node)"
            />
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-pencil"
              :aria-label="t('files.rename', { name: row.node.name })"
              severity="secondary"
              outlined
              size="small"
              @click="emit('rename', row.node)"
            />
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-trash"
              :aria-label="t('files.delete', { name: row.node.name })"
              severity="danger"
              outlined
              size="small"
              @click="remove(row.node)"
            />
          </template>
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.files-toolbar {
  display: flex;
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
