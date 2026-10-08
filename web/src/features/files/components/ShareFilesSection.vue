<script setup lang="ts">
// Files' section of a public folder share (cr-files-application F2): what core's `core/sharing/FolderShareBody.vue`
// renders for the FILE children of the folder on screen (`register.ts` names it as FILE's `shareSection`). Every guest
// of a READ or READ_WRITE link sees the files with their name, size and modified time (opened under the keys of the
// chain) and downloads them through the link (`resolveShareBlob`: the share's subtree gate — a file in the trash is not
// served, nor listed). A READ_WRITE guest also uploads into the folder and replaces a file's content; the guest names
// itself first (`requestGuest`, core's one session per link). No rename or delete here: a guest deletes nothing
// (ADR 0080 Decision 6), and the guest page offers no rename for any kind yet (inbox #358).
//
// Operator-blind: the names, sizes, types and dates are opened here; Files' only call is `listShareFiles` (ids and
// sealed sizes) and, to write, `completeShareFile` (ids and the sealed meta).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import * as v1 from '@veyq/crypto/v1'
import type { ShareSectionProps } from '../../../core/applications/registry'
import { enqueue, removeFile, useUploadQueue, QUOTA_EXCEEDED, type UploadItem } from '../../../core/blob/queue'
import { guestTreeContext } from '../../../core/sharing/guestTree'
import { shareChunkUrls } from '../../../core/sharing/guestBlob'
import { useToast } from '../../../shared/state/useToast'
import { formatBytes } from '../../../shared/formatBytes'
import type { FileBlobRef } from '../../../api/generated/files'
import type { ShareNode } from '../../../api/generated/sharing'
import { publicShareFilesApi } from '../api'
import { FILE_KIND } from '../kinds'
import { readFileMeta } from '../fileMeta'
import { FileUploadError, type FileJobExtra, type FileUploadResult } from '../uploadFile'
import { guestFileUploadJob } from '../shareUpload'
import { downloadFile } from '../download'
import { isSaveCancelled, SaveTooLarge } from '../../../shared/saveStream'

const props = defineProps<ShareSectionProps>()

const { t, locale } = useI18n()
const toast = useToast()
const { batches } = useUploadQueue()

const fileNodes = computed(() => props.nodes.filter((n) => n.kind === FILE_KIND && props.names.has(n.id)))

// --- the blobs of the share's files (`listShareFiles`, the whole live subtree in one read) ----------------------

const blobs = ref(new Map<string, FileBlobRef | null>())
let loads = 0
async function loadBlobs(): Promise<void> {
  const mine = ++loads
  try {
    const page = await publicShareFilesApi().listShareFiles({ shareId: props.shareId, xSharePassword: props.pwToken })
    if (mine === loads) blobs.value = new Map(page.items.map((i) => [i.nodeId, i.fileBlob ?? null]))
  } catch {
    if (mine === loads) blobs.value = new Map() // names still show; download waits for the next load
  }
}
// A new head is a re-resolve (after an upload, or the shell's reload): the blobs may have moved with it.
watch(() => props.head, () => void loadBlobs(), { immediate: true })

// --- the rows ----------------------------------------------------------------------------------------------------

const dateFormat = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))

const rows = computed(() =>
  fileNodes.value
    .map((node) => {
      const key = props.keys.get(node.id) ?? null
      const name = props.names.get(node.id) ?? ''
      const meta = key ? readFileMeta(key, { scopeId: props.head.scopeId, nodeId: node.id }, node.wrappedMeta) : null
      const blob = blobs.value.get(node.id) ?? null
      const size = meta?.size ?? (blob ? Math.max(0, blob.byteSize - v1.FRAME_OVERHEAD * blob.chunkCount) : null)
      const detail = blob
        ? [size === null ? null : formatBytes(size, locale.value), meta ? t('files.modified', { date: dateFormat.value.format(meta.modifiedAt) }) : null]
            .filter((s): s is string => s !== null)
            .join(' · ')
        : t('files.notUploaded')
      return { node, key, name, blob, detail }
    })
    .sort((a, b) => a.name.localeCompare(b.name, locale.value)),
)
type Row = (typeof rows.value)[number]

// --- upload and replace (READ_WRITE) -----------------------------------------------------------------------------

const picker = ref<HTMLInputElement | null>(null)
const replacePicker = ref<HTMLInputElement | null>(null)
const replaceTarget = ref<ShareNode | null>(null)
const batchKey = computed(() => `files-share:${props.shareId}:${props.folderId}`)

function refresh(): void {
  void props.reload() // the new node lists with its name and key; the new head reloads the blobs
}

/** Enqueue one guest job per file — into a new FILE under the folder, or into `replaceNode` (the row's replace). A
 *  name a sibling already takes is refused by core's guest create before anything is sent (the notice says so). */
async function startUploads(files: File[], replaceNode?: ShareNode): Promise<void> {
  if (!props.canWrite || files.length === 0) return
  const guest = await props.requestGuest()
  if (!guest) return
  const ctx = guestTreeContext(props.head, { contributorToken: guest.contributorToken, fragmentKey: props.fragmentKey, pwToken: props.pwToken })
  const nodeKey = replaceNode ? ctx.keys.get(replaceNode.id) : undefined
  if (replaceNode && !nodeKey) return
  const jobs = files.map((file) =>
    replaceNode && nodeKey
      ? guestFileUploadJob({
          ctx, parentId: props.folderId,
          replace: { nodeId: replaceNode.id, nodeKey, wrappedMeta: replaceNode.wrappedMeta ?? null, expectedBlobId: blobs.value.get(replaceNode.id)?.blobId ?? null },
        }, file)
      : guestFileUploadJob({ ctx, parentId: props.folderId }, file),
  )
  enqueue<FileUploadResult, FileJobExtra>(jobs, {
    label: props.folderId === ctx.rootId ? props.head.name : (props.names.get(props.folderId) ?? t('files.title')),
    key: batchKey.value,
    onItemDone: refresh,
    onComplete: refresh, // a failed upload may still have created its node
  })
}

const pickedFiles = (e: Event): File[] => {
  const input = e.target as HTMLInputElement
  const files = [...(input.files ?? [])]
  input.value = ''
  return files
}
function onPicked(e: Event) {
  void startUploads(pickedFiles(e))
}
function startReplace(node: ShareNode) {
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
  if (props.canWrite) void startUploads([...(e.dataTransfer?.files ?? [])])
}

// --- what the status menu cannot explain -------------------------------------------------------------------------

type Notice = { item: UploadItem<FileUploadResult, FileJobExtra>; text: string }
const KNOWN_ERRORS = new Set<string>(Object.values(FileUploadError))
const notices = computed<Notice[]>(() =>
  batches.value
    .filter((b) => b.key === batchKey.value)
    .flatMap((b) => b.files as UploadItem<FileUploadResult, FileJobExtra>[])
    .filter((item) => item.status === 'failed')
    .map((item) => {
      const error = item.error ?? ''
      const text = error === QUOTA_EXCEEDED
        ? t('files.guest.linkFull', { name: item.name })
        : t(`files.errors.${KNOWN_ERRORS.has(error) ? error : 'failed'}`, { name: item.name })
      return { item, text }
    }),
)

// --- download ----------------------------------------------------------------------------------------------------

const downloading = ref<string | null>(null)
async function download(row: Row) {
  if (!row.key || !row.blob || downloading.value) return
  downloading.value = row.node.id
  try {
    // Nothing awaited before it: the save-file picker needs this click's activation (`openSaveTarget`).
    await downloadFile(props.head.scopeId, row.key, row.node.id, row.blob, row.name, (blobId) => shareChunkUrls(props.shareId, blobId, props.pwToken))
  } catch (e) {
    if (e instanceof SaveTooLarge) toast.error(t('files.downloadTooLarge'))
    else if (!isSaveCancelled(e)) toast.error(t('files.downloadFailed'))
  } finally {
    downloading.value = null
  }
}
</script>

<template>
  <section class="veyq-section share-files-section" @dragover.prevent @drop.prevent="onDrop">
    <div class="veyq-section-header">
      <h2>{{ t('files.title') }}</h2>
      <span>{{ fileNodes.length }}</span>
      <div v-if="canWrite" class="share-files-toolbar">
        <Button type="button" :label="t('files.upload')" icon="pi pi-upload" severity="secondary" outlined size="small" @click="picker?.click()" />
      </div>
    </div>
    <template v-if="canWrite">
      <input ref="picker" class="share-files-picker" type="file" multiple hidden @change="onPicked" />
      <input ref="replacePicker" class="share-files-picker" type="file" hidden @change="onReplacePicked" />
    </template>

    <Message v-for="n in notices" :key="n.item.id" severity="error" :closable="false">
      <span class="share-files-notice-body">
        <span>{{ n.text }}</span>
        <Button type="button" :label="t('files.dismiss')" size="small" severity="secondary" text @click="removeFile(n.item.id)" />
      </span>
    </Message>

    <p v-if="!rows.length" class="veyq-empty veyq-empty-inline">{{ canWrite ? t('files.empty') : t('files.guest.empty') }}</p>
    <ul v-else class="veyq-list">
      <li v-for="row in rows" :key="row.node.id" class="veyq-list-row share-files-row" :data-node-id="row.node.id">
        <span class="pi pi-file" aria-hidden="true" />
        <span class="veyq-row-main">
          <span class="veyq-row-title">{{ row.name }}</span>
          <span class="veyq-row-subtitle">{{ row.detail }}</span>
        </span>
        <span class="veyq-action-group veyq-action-group-icons">
          <Button
            type="button"
            class="veyq-icon-button veyq-prime-icon-button"
            icon="pi pi-download"
            :aria-label="t('files.download', { name: row.name })"
            severity="secondary"
            outlined
            size="small"
            :loading="downloading === row.node.id"
            :disabled="!row.blob || !row.key || downloading !== null"
            @click="download(row)"
          />
          <Button
            v-if="canWrite"
            type="button"
            class="veyq-icon-button veyq-prime-icon-button"
            icon="pi pi-sync"
            :aria-label="t('files.replace', { name: row.name })"
            severity="secondary"
            outlined
            size="small"
            @click="startReplace(row.node)"
          />
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.share-files-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  margin-left: auto;
}
.share-files-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}
.share-files-notice-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  width: 100%;
}
</style>
