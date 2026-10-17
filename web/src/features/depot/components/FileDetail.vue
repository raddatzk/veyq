<script setup lang="ts">
// An opened file (inbox #378): the page of ONE file — core's header with the file's actions (download, replace of its
// content, share, rename, move, delete, each where Depot's matrix says so; owner decision 2026-10-14: the download and the
// replace are header actions, no longer buttons below it), then the file itself and its size, type and modified time
// opened from the blob's own sealed meta. It is FILE's `detail` in the registry, so the host mounts it for a member who
// opens a file from the folder's list, and core's page of a public link (`ShareTreePage`) mounts the very same component
// for a guest at the link's root — a single-file link — or at a file inside a folder link (inbox #401: a share is the
// member's view with functions removed, never a page of its own).
//
// **What it offers is `depotRights(access, targetOf(tree, file))` narrowed to what the data source carries**
// (`../rights.ts`, `../source.ts`): a member everything its row offers plus share, rename, move and delete of the
// file; a `READ` guest the download; a `READ_WRITE` guest the download and the replace — after the question that says it
// changes the file for everyone who sees it, the owner too (inbox #383); no standing sentence says so beforehand — and,
// for a file inside a folder link, the move between the subtree's folders. A guest renames and deletes nothing (whether
// a guest may delete content of its own is inbox #467), and a `WRITE_ONLY` link on a file does not exist (the backend
// refuses it).
//
// The size, type and date are the BLOB's (`download.ts` `fileFacts`): the one listing the source offers
// (`listBlobs` — a member's reads the file's folder, a guest's the link) says which blob the file holds, and the bytes
// download through the session's own door. The replace is a compare-and-set against the blob this page read
// (`expectedBlobId`): a peer's replace in the meantime refuses it (`stale_file`) and the page offers "replace anyway".
// A row of the folder's list offers no replace (owner decision 2026-10-14: there, an upload of the same name replaces);
// this page keeps it, because it is the one way a `READ_WRITE` link on a single file writes — it has no folder above it.
//
// **The file is shown when its page opens** (inbox #412, `FileViewer`): an image, a text, a video or audio through the service
// worker's media route, the download offered for anything else; Depot makes no thumbnail or preview blob. It reads the blob
// through the source's `chunks` (a member's scope READ gate, a guest's link), so a guest of either role sees the member's
// viewer; nothing about it is a right of its own beyond the download it offers where the matrix grants one.
//
// **Who uploaded the current content** (ADR 0083 Decision 8, `../uploader.ts`): a member's page names them in the header's meta
// line - a member by core's display rule with a link to the profile, one who left as a greyed "former member", a guest
// by the name it gave itself (inbox #431: its identity folder inside a drop box, else its sealed name opened under the
// nearest key the tree holds) and marked as a guest's ("Ben (guest)", core's `guestDisplayName`, inbox #433), "a guest"
// where that does not open. It comes from the member listing's `uploaderKey` and
// `uploaderName`, a door only the member's source carries (`listWithUploaders`): a share guest's source has none and its
// link listing serves no uploader, so a guest's page names nobody and asks no directory.
//
// Operator-blind as the section: every name, size and date is opened here under keys the tree holds.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import type { NodeDetailEmits, NodeDetailProps } from '../../../core/applications/registry'
import NodeHeader from '../../../core/node/components/NodeHeader.vue'
import { standardNodeActions, type NodeHeaderAction } from '../../../core/node/nodeHeader'
import { favoriteHeaderActions } from '../../../core/favorites/favoriteActions'
import { copyHeaderActions } from '../../../core/export/copyActions'
import { reportHeaderActions, reportNodes } from '../../../core/report/reportActions'
import { useReportOffered } from '../../../core/report/report'
import { enqueue, removeFile, useUploadQueue, QUOTA_EXCEEDED, type UploadItem } from '../../../core/blob/queue'
import { useConfirm } from '../../../shared/state/useConfirm'
import { useToast } from '../../../shared/state/useToast'
import { formatBytes } from '../../../shared/formatBytes'
import { isSaveCancelled, SaveTooLarge } from '../../../shared/saveStream'
import type { FileBlobRef, FileUploaderName } from '../../../api/generated/depot'
import { targetOf, type GuestAccess } from '../../../core/access'
import { depotRights } from '../rights'
import { guestDepotSource, memberDepotSource, sourceRights, type DepotSource } from '../source'
import { deliveredSince, FileUploadError, type FileJobExtra, type FileUploadResult } from '../uploadFile'
import { fileFacts } from '../download'
import { extensionOf } from '../../../core/blob/sniff'
import type { ViewerTarget } from '../useFileViewer'
import FileViewer from './FileViewer.vue'
import PersonAvatar from '../../../core/people/PersonAvatar.vue'
import { personHash } from '../../../core/node/location'
import { loadScopePeople, type Person } from '../../../core/people/directory'
import { guestUploaderName, needsPeople, uploaderOf, type FileUploader } from '../uploader'
import { guestDisplayName } from '../../../core/sharing/guestIdentity'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()
const { t, locale } = useI18n()
// Core's heart (inbox #409): the same for every kind; a guest gets none.
// A file downloads as itself (the header's own download): core adds no ZIP download for it (ADR 0082 decision 4).
// …and core's "Copy to…" (uc.platform.copy-node) beside the move, a member's.
// Core's Report (uc.moderation.report) beside the heart and the copy, for whoever sees the file.
const reportOn = useReportOffered()
const coreActions = computed(() => [
  ...favoriteHeaderActions(t, props.access, props.node),
  ...copyHeaderActions(t, props.access, props.node, () => emit('copy')),
  ...reportHeaderActions(t, reportOn.value, props.access, props.node, () => reportNodes(props.access, props.tree, [props.node], null)),
])
const { ask } = useConfirm()
const toast = useToast()
const { batches } = useUploadQueue()

// One session for the view's life (see `../levelRows.ts`): the member's source or the link's, fixed at mount.
const guest = props.access.mode === 'guest'
const source: DepotSource = guest
  ? guestDepotSource(
      (): GuestAccess => {
        const a = props.access
        if (a.mode !== 'guest') throw new Error('a guest view was handed a member access')
        return a
      },
      { move: (node) => emit('move', node) },
    )
  : memberDepotSource({
      scopeId: () => props.scopeId,
      epochs: () => props.epochs,
      keyOf: (id) => props.tree.keyOf(id),
      rename: () => emit('rename'),
      move: (node) => emit('move', node),
    })
const rights = computed(() => sourceRights(depotRights(props.access, targetOf(props.tree, props.node)), source))
const key = computed(() => props.contentKey ?? props.tree.keyOf(props.node.id))
const writeReady = computed(() => source.upload?.ready() ?? false)

// --- the file's blob -------------------------------------------------------------------------------------------------

/** `undefined`: not read yet (or the read failed); `null`: the file holds no content; else its blob. */
const blob = ref<FileBlobRef | null | undefined>(undefined)
/** Who uploaded `blob` (`user:<id>` | `share:<identity>`), read with it where the source carries the door (a member), and
 *  a guest uploader's sealed name with its share (inbox #431). */
const uploaderKey = ref<string | null>(null)
const uploaderName = ref<FileUploaderName | null>(null)
let loads = 0
async function loadBlob(): Promise<void> {
  const mine = ++loads
  if (!source.scopeId()) return
  const parentId = props.node.parentId ?? null
  try {
    if (source.listWithUploaders) {
      const listed = (await source.listWithUploaders(parentId)).get(props.node.id)
      if (mine !== loads) return
      blob.value = listed?.blob ?? null
      uploaderKey.value = listed?.uploaderKey ?? null
      uploaderName.value = listed?.uploaderName ?? null
    } else {
      const listed = await source.listBlobs(parentId)
      if (mine === loads) blob.value = listed.get(props.node.id) ?? null
    }
  } catch {
    if (mine === loads) blob.value = undefined // download stays unavailable until the next read
  }
}
watch(() => [source.listKey(), props.node.id], () => void loadBlob(), { immediate: true })

/** The scope's current members as read for `key` (a member's key asks for them; a failed read is null and names nobody). */
const people = ref<{ key: string | null; byId: ReadonlyMap<string, Person> | null }>({ key: null, byId: null })
let namings = 0
watch(
  uploaderKey,
  async (k) => {
    const mine = ++namings
    const scopeId = source.scopeId()
    const byId = needsPeople(k) && scopeId
      ? await loadScopePeople(scopeId).then((ps) => new Map(ps.map((p) => [p.userId, p]))).catch(() => null)
      : null
    if (mine === namings) people.value = { key: k, byId }
  },
  { immediate: true },
)
/** The uploader as the header names it: a member once the members were read for this key; a guest by the name it gave
 *  itself, opened under the keys the tree holds - re-asked when they open (a member's tree loads after the listing). */
const uploader = computed<FileUploader | null>(() => {
  const k = uploaderKey.value
  const scopeId = source.scopeId()
  const byId = people.value.key === k ? people.value.byId : null
  return uploaderOf(k, byId, (identityId) =>
    scopeId ? guestUploaderName(props.tree, scopeId, props.node.id, identityId, uploaderName.value) : null,
  )
})

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

/** The header's actions (owner decision 2026-10-14): the download, the replace of the content (an upload into a file that
 *  holds none), then share, rename, move, delete — each only where the rights matrix grants it. The download is the
 *  page's call to action; it runs inside the click (the save picker needs its activation). */
const headerActions = computed<NodeHeaderAction[]>(() => [
  {
    id: 'download', label: t('depot.download', { name: props.node.name }), icon: 'pi pi-download', severity: 'primary',
    class: 'file-detail-download', shown: rights.value.download, disabled: !blob.value || !key.value || downloading.value,
    onClick: () => void download(),
  },
  {
    id: 'replace', label: t(empty.value ? 'depot.uploadInto' : 'depot.replace', { name: props.node.name }),
    icon: empty.value ? 'pi pi-upload' : 'pi pi-sync', class: 'file-detail-replace', shown: rights.value.replace,
    disabled: !writeReady.value, onClick: () => picker.value?.click(),
  },
  ...standardNodeActions(
    t,
    { share: rights.value.share, rename: rights.value.rename, move: rights.value.moveNode, remove: rights.value.deleteNode },
    { share: () => emit('share', { contribute: rights.value.shareContribute }), rename: () => emit('rename'), move: () => source.move?.(props.node), remove: () => emit('delete') },
  ),
])

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
    if (e instanceof SaveTooLarge) toast.error(t('depot.downloadTooLarge'))
    else if (!isSaveCancelled(e)) toast.error(t('depot.downloadFailed'))
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
  if (!upload || !upload.ready() || !rights.value.replace || !nodeKey) return
  const batch = upload.beginReplace()
  if (!batch) return
  if (!confirmed && !empty.value) {
    const accepted = await ask({
      header: t('depot.replaceTitle'),
      message: t(guest ? 'depot.guest.replaceFileConfirm' : 'depot.replaceRowConfirm', { name: props.node.name, file: file.name }),
      acceptLabel: t('depot.replaceAccept'),
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
  if (guest && code === QUOTA_EXCEEDED) return t('depot.guest.linkFull', { name })
  return t(`depot.errors.${code}`, { name })
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
    <NodeHeader icon="pi pi-file" :title="node.name" :level="1" :actions="headerActions" :core-actions="coreActions">
      <template v-if="uploader" #meta>
        <p class="file-detail-uploader" data-testid="file-uploader">
          <!-- The space is real text, so the line reads "Uploaded by Anna" to assistive technology too; the avatar is decorative. -->
          <span class="file-detail-uploader-label">{{ t('depot.detail.uploadedBy') }}</span>{{ ' ' }}
          <RouterLink v-if="uploader.kind === 'member'" :to="{ hash: personHash(uploader.userId) }" class="file-detail-uploader-person">
            <PersonAvatar :name="uploader.name" :user-id="uploader.userId" />
            <span class="file-detail-uploader-name">{{ uploader.name }}</span>
          </RouterLink>
          <span v-else-if="uploader.kind === 'former'" class="file-detail-uploader-person is-former" :title="t('people.formerMemberHint')">
            <PersonAvatar :name="t('people.formerMember')" />
            <span class="file-detail-uploader-name">{{ t('people.formerMember') }}</span>
          </span>
          <!-- A guest by the name it gave itself, marked as a guest's (inbox #433: a guest may pick a member's name; no link: a
               guest has no profile), else "a guest". -->
          <span v-else class="file-detail-uploader-person file-detail-uploader-name">{{ uploader.name ? guestDisplayName(uploader.name) : t('depot.detail.aGuest') }}</span>
        </p>
      </template>
    </NodeHeader>
    <section class="veyq-section file-detail">
      <Message v-for="n in notices" :key="n.key" severity="error" :closable="false" class="files-notice">
        <span class="file-detail-notice">
          <span>{{ n.text }}</span>
          <span class="veyq-action-group">
            <Button
              v-if="n.replaceAnyway && rights.replace"
              type="button"
              :label="t('depot.replaceAnyway')"
              size="small"
              severity="secondary"
              outlined
              @click="replaceAnyway(n)"
            />
            <Button type="button" :label="t('depot.dismiss')" size="small" severity="secondary" text @click="removeFile(n.item.id)" />
          </span>
        </span>
      </Message>

      <!-- The file itself, above its facts (inbox #412). -->
      <FileViewer v-if="!empty" :target="viewTarget" :name="node.name" :can-download="rights.download" @download="download" />
      <p v-if="empty" class="veyq-empty veyq-empty-inline">{{ t('depot.notUploaded') }}</p>
      <dl v-else-if="facts" class="file-detail-facts">
        <dt>{{ t('depot.detail.size') }}</dt>
        <dd>{{ formatBytes(facts.size, locale) }}</dd>
        <dt>{{ t('depot.detail.type') }}</dt>
        <dd>{{ facts.type ?? t('depot.detail.typeUnknown') }}</dd>
        <template v-if="facts.modifiedAt !== null">
          <dt>{{ t('depot.detail.modified') }}</dt>
          <dd>{{ dateFormat.format(facts.modifiedAt) }}</dd>
        </template>
      </dl>

      <!-- The replace's picker (the header's replace opens it); the replace asks when a file is picked (inbox #383). -->
      <input v-if="rights.replace" ref="picker" class="files-picker" type="file" hidden @change="onPicked" />
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
.file-detail-uploader {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem;
  margin: 0.25rem 0 0;
  color: var(--app-muted);
  font-size: 0.92rem;
}
.file-detail-uploader-person {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  color: var(--app-text);
  font-weight: 600;
  text-decoration: none;
}
a.file-detail-uploader-person:hover {
  text-decoration: underline;
}
a.file-detail-uploader-person:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
  border-radius: var(--app-radius-sm);
}
.file-detail-uploader-person.is-former {
  color: var(--app-muted);
  font-weight: 500;
  font-style: italic;
}
.file-detail-uploader-person.is-former :deep(.p-avatar) {
  opacity: 0.6;
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
