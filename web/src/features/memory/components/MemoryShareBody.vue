<script setup lang="ts">
// Memories' drop page: the page of a `WRITE_ONLY` link rooted at a Memory or a chapter (uc.sharing.share-uploader,
// cr-0102, ADR 0031; claimed by `register.ts`, mounted by the shell `views/ShareView.vue` behind its password and terms
// gates for that role only). Its guest holds no key to the Memory — only the node's drop PUBLIC key, enough to wrap an
// identity key of its own to (ADR 0031 point 7) — and is served no media, so there is nothing to render the member's
// way: this is the one guest-only page of Memories (ADR 0079 Consequences, inbox #401). A `READ` or `READ_WRITE` link of
// a Memory or chapter gets the member's `MemoryDetail` through core's page instead (`core/sharing/ShareTreePage.vue`).
//
// What it holds: the name pre-screen (core's one naming form, `GuestNaming`, with the uploader's ephemerality warning and
// the curator's note), then the drop — "Add photos", the in-flight and settled tiles of THIS session (the member
// gallery's `OptimisticTile`, laid out by the shared `MediaGrid`), each settled one deletable while this session holds
// its media id, and the page's own "Not you?". Session-only by design (ADR 0031 §5): after a reload the queue is empty
// and the page says so. Operator-blind: every upload seals on the device under the guest's own key.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  openSharedMemory,
  createContributorIdentity,
  sessionSealKey,
  addSharePhoto,
  deleteSharePhoto,
  type ShareAccess,
  type ContributorSession,
} from '../share'
import { pickLocaleText, type PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { forgetGuestSession, saveGuestSession, useGuestSession } from '../../../core/sharing/guestIdentity'
import type { GuestAccess } from '../../../core/access'
import GuestNaming from '../../../core/sharing/GuestNaming.vue'
import { collectPickedFiles, inferMediaType, enqueueUploads, retryFile, removeFile } from '../uploadQueue'
import { useOptimisticTiles } from '../useOptimisticTiles'
import { byCaptureTime } from '../optimisticUpload'
import { renderMarkdown } from '../../../shared/markdown'
import { ShareRole } from '../../../api/generated/sharing'
import MediaGrid from './MediaGrid.vue'
import OptimisticTile from './OptimisticTile.vue'

// The props are the registry's `ShareBodyProps` (core/applications/registry.ts), spelled out because the SFC compiler
// reads them statically.
const props = defineProps<{
  shareId: string
  fragmentKey: string
  pwToken: string | undefined
  head: PublicShareHead
  reload: () => Promise<void>
  access: GuestAccess
}>()
const emit = defineEmits<{ unavailable: [] }>()

const { t, locale } = useI18n()

// A share's id never changes under a mounted page (the shell re-mounts per route).
const shareId = props.shareId
// cr-0115: the curator's note — on a drop page the instruction ("please upload here") — for the viewer's locale
// (cr-0116), as markdown (the safe escape-first subset, so v-html is safe).
const renderedNote = computed(() => {
  const note = pickLocaleText(props.head.noteByLocale, locale.value)
  return note ? renderMarkdown(note) : ''
})

// The drop public key and the node it belongs to (ADR 0031 point 7) — never a `K_node`. Opened from the shell's head on
// mount and after every re-resolve; a head this page cannot open is the shell's honest unavailable state.
const keyAccess = ref<ShareAccess | null>(null)
watch(
  () => props.head,
  (head) => {
    try {
      if (head.role !== ShareRole.WriteOnly) throw new Error('not_a_drop') // the shell mounts this for WRITE_ONLY only
      keyAccess.value = openSharedMemory(head).access
    } catch {
      emit('unavailable')
    }
  },
  { immediate: true },
)

// The named session, persisted per link so a reload keeps the name (it never leaves the device): the platform's
// (`core/sharing/guestIdentity.ts`), reactive — read before the first render (no name-gate flash) and gone at once when
// "Not you?" forgets it.
const session = useGuestSession<ContributorSession>(shareId)
// The key this session's uploads seal under — the uploader's own identity key.
const sealKey = computed(() => (session.value && keyAccess.value ? sessionSealKey(session.value, keyAccess.value) : null))
// The name pre-screen until the visitor has named itself — and again for a session saved without its own key, which
// has nothing to seal with.
const needsName = computed(() => !!keyAccess.value && !sealKey.value)
/** The pre-screen's naming (core's `GuestNaming` renders the form): the identity, its name kept on the device. */
async function submitName(name: string) {
  if (!keyAccess.value) throw new Error('share_not_open')
  const created = await createContributorIdentity(shareId, props.head.scopeId, keyAccess.value, name, props.fragmentKey, props.pwToken)
  saveGuestSession(shareId, { ...created, displayName: name })
}
/** "Not you?" (the drop page's own line): forget this device's session — the next visitor names itself afresh. What
 *  was sent stays with the owner. */
function forgetUploader() {
  forgetGuestSession(shareId)
}

// The session's own tiles (cr-0074): the member gallery's composable, grouped by this link. Nothing is ever listed for
// an uploader, so every tile is an optimistic one, and a settled one stays as the session's own view.
const noListedMedia = computed(() => new Set<string>())
const { optimisticTiles } = useOptimisticTiles(computed(() => shareId), noListedMedia)
// Newest first by the capture time each photo is sealed with (the member gallery's comparator, uc.memory.gallery).
const tiles = computed(() =>
  byCaptureTime(optimisticTiles.value, (o) => o.capturedAt, -1).map((o) => ({ id: o.layoutId, aspectRatio: o.aspectRatio, optimistic: o })),
)

// Add photos: the picked files go on the SHARED upload queue with the link's transport, as optimistic tiles at once.
// The pick runs through `collectPickedFiles`, the same uc.memory.upload E3 window the curator's picker uses: Safari's
// Photos gallery populates `input.files` late, and reading it once would drop a photo silently.
const fileInput = ref<HTMLInputElement | null>(null)
let cancelPickWindow: (() => void) | null = null
function onFilesPicked() {
  const input = fileInput.value
  if (!input) return
  cancelPickWindow?.() // a fresh pick supersedes whatever the previous window was still waiting for
  cancelPickWindow = collectPickedFiles(input, enqueueBatch)
}
function enqueueBatch(picked: File[]) {
  const s = session.value
  const key = sealKey.value
  if (!s || !key) return
  enqueueUploads(
    {
      key: shareId,
      // Into the link's root (a WRITE_ONLY link writes there only): no target node is named.
      upload: (source, opts) => addSharePhoto({ shareId, scopeId: props.head.scopeId, sealKey: key, ...s, pwToken: props.pwToken }, source, undefined, opts),
    },
    picked.map((file) => ({ file, mediaType: inferMediaType(file) })),
    // Never a re-resolve: the resolve serves an uploader no media; the settled tile is the session's own view.
    { label: props.head.name },
  )
}

// Delete-your-own (cr-0102): the settled tile carries the returned media id — the token-gated delete, then the file
// leaves the queue (its tile and local preview go). Session-only: after a reload the device no longer holds the id.
const deletingIds = ref<Set<string>>(new Set())
const deleteError = ref(false)
async function onDeleteUpload(fileId: string) {
  const mediaId = optimisticTiles.value.find((x) => x.fileId === fileId)?.mediaId
  const s = session.value
  deleteError.value = false
  if (!mediaId || !s) {
    removeFile(fileId) // never settled to a media id — just drop the local tile
    return
  }
  deletingIds.value = new Set(deletingIds.value).add(fileId)
  try {
    await deleteSharePhoto(shareId, s.contributorToken, mediaId, props.pwToken)
    removeFile(fileId)
  } catch {
    deleteError.value = true // keep the tile so the visitor can retry the delete this session
  } finally {
    const next = new Set(deletingIds.value)
    next.delete(fileId)
    deletingIds.value = next
  }
}

onBeforeUnmount(() => cancelPickWindow?.()) // the E3 re-read window owns its teardown — no timer outlives the page
</script>

<template>
  <!-- The mandatory display-name pre-screen before the first photo (ADR 0031) -->
  <div v-if="needsName" class="share-view-state share-name-gate">
    <div class="veyq-surface share-name-card">
      <h1>{{ head.name }}</h1>
      <p class="share-name-intro">{{ t('share.uploaderIntro') }}</p>
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div v-if="renderedNote" class="share-note markdown-body" v-html="renderedNote"></div>
      <!-- warning #1 of 4 (ADR 0031 §5): the session-only ephemerality, up front before the first upload -->
      <p class="share-uploader-warning">
        <span class="pi pi-info-circle" />
        <span>{{ t('share.uploaderEphemeralWarning') }}</span>
      </p>
      <!-- core's one naming form; who reads the name: the Memory's members, never another link holder (ADR 0030 point 8) -->
      <GuestNaming :action="submitName" :hint="t('share.nameVisibility')" />
    </div>
  </div>

  <template v-else-if="keyAccess">
    <header class="gallery-header share-view-header">
      <div class="gallery-header-copy">
        <h1 class="gallery-title">{{ head.name }}</h1>
        <!-- warning #2 of 4: the persistent badge -->
        <span class="share-view-badge">
          <span class="pi pi-cloud-upload" />
          {{ t('share.uploaderBadge') }}
        </span>
        <GuestNaming v-if="session" :current="session" :forget="forgetUploader" />
      </div>
      <div class="share-view-actions">
        <button type="button" class="share-add-button" @click="fileInput?.click()">
          <span class="pi pi-plus" />
          <span>{{ t('share.addPhotos') }}</span>
        </button>
        <input
          ref="fileInput"
          type="file"
          multiple
          accept="image/*,video/*,.heic,.heif,.mov,.m4v,.mkv,.avi,.tif,.tiff"
          class="visually-hidden"
          @change="onFilesPicked"
        />
      </div>
    </header>

    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-if="renderedNote" class="share-note markdown-body" v-html="renderedNote"></div>

    <!-- warning #4 of 4 (ADR 0031 §5): a persistent note that the own-view is session-only + delete-in-session -->
    <p v-if="tiles.length" class="share-uploader-note">
      <span class="pi pi-info-circle" />
      <span>{{ t('share.uploaderSessionNote') }}</span>
    </p>
    <p v-if="deleteError" class="share-error">{{ t('share.deleteFailed') }}</p>

    <!-- warning #3 of 4: the reload empty state — after a reload the session's own view is gone (not a blank grid) -->
    <p v-if="!tiles.length" class="veyq-empty">{{ t('share.uploaderEmpty') }}</p>
    <MediaGrid v-else :items="tiles" :aria-label="head.name">
      <template #tile="{ tile, item }">
        <OptimisticTile
          :tile="item.optimistic"
          deletable
          :deleting="deletingIds.has(item.optimistic.fileId)"
          link
          :style="{
            transform: `translate(${tile.left}px, ${tile.top}px)`,
            width: `${tile.renderedWidth}px`,
            height: `${tile.renderedHeight}px`,
          }"
          @retry="retryFile"
          @delete="onDeleteUpload"
        />
      </template>
    </MediaGrid>
  </template>
</template>

<style scoped src="../../../shared/ui/shareGate.css"></style>

<style scoped>
/* The drop page. The state/card look (name pre-screen) comes from the shared gate sheet above; the grid
   (.media-grid) and the tiles come from the GLOBAL design-source stylesheets via the shared components. */
.share-view-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 1.15rem;
  margin-bottom: 1.15rem;
  border-bottom: 1px solid var(--app-line);
}
.share-view-actions {
  flex: 0 0 auto;
}
.share-add-button {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0.5rem 0.9rem;
  border: 0;
  border-radius: 999px;
  background: var(--app-accent);
  color: var(--app-on-fill);
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
}
/* The uploader badge is a persistent warning tone (session-only own-view). */
.share-view-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  margin-top: 0.15rem;
  color: var(--app-warning-text);
  font-size: 0.82rem;
  font-weight: 700;
}
.share-view-badge .pi {
  font-size: 0.85rem;
}
/* The uploader's ephemerality warning in the name pre-screen + the persistent session note above the grid. */
.share-uploader-warning,
.share-uploader-note {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  margin: 0.2rem 0 0.5rem;
  padding: 0.6rem 0.75rem;
  border: 1px solid color-mix(in srgb, var(--app-warning) 45%, var(--app-line));
  border-radius: 10px;
  background: color-mix(in srgb, var(--app-warning) 10%, transparent);
  color: var(--app-text);
  font-size: 0.85rem;
  line-height: 1.4;
}
.share-uploader-note {
  margin-bottom: 1rem;
}
.share-uploader-warning .pi,
.share-uploader-note .pi {
  margin-top: 0.1rem;
  color: var(--app-warning-text);
}
/* cr-0115: the curator note — a card banner under the header / the instruction in the name pre-screen. The inner
   elements come from the safe markdown renderer via v-html, so they need :deep. */
.share-note {
  margin: 0 0 1.15rem;
  padding: 0.85rem 1rem;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-surface-muted);
  color: var(--app-text);
  font-size: 0.95rem;
  line-height: 1.55;
}
.share-name-card .share-note {
  margin: 0.1rem 0 0.3rem;
}
.share-note :deep(p) {
  margin: 0 0 0.55rem;
}
.share-note :deep(p:last-child) {
  margin-bottom: 0;
}
.share-note :deep(ul),
.share-note :deep(ol) {
  margin: 0 0 0.55rem;
  padding-left: 1.3rem;
}
.share-note :deep(a) {
  color: var(--app-accent);
}
</style>
