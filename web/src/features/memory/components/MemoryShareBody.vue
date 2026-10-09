<script setup lang="ts">
// The Memories body of the public share page (uc.sharing.share-public-viewer, cr-0100, ADR 0029; the shell is
// `views/ShareView.vue`). The shell has already resolved the kind-independent half (`head`: fragment key, password,
// `K_share`, name, note, role) and run the password and terms gates; this body opens the Memory's own seals from it
// (`openSharedMemory`: the root `K_mem`, the chapters' keys, the media; an uploader's drop key) and renders the Memory
// **read-only** with the SAME components as the internal Memory — the shared [MediaGrid] (justified layout) +
// [MediaLightbox] (in `readonly` mode, with the operator-blind per-item share byte-loaders injected) — so a share looks
// exactly like the Memory it mirrors, minus the curator-only affordances. It also owns what only Memories has: the
// contributor/uploader name pre-screen, the photo picker and the upload tiles, the grouping of the media by chapter
// (#356) and a contributor's delete of its own photos (#361). Operator-blind: media bytes are fetched
// direct-from-store via gated presigned GETs, so the operator never learns which media a viewer opens.
//
// Claimed by `register.ts` for both Memories root kinds — a Memory and a chapter, whose share opens the same way: the
// root's `K_mem` from the wrap, its title from the share's sealed name, the chapters below it — for every role (a WRITE_ONLY share is the drop), the order the shell
// runs the gates in is password -> terms -> name -> content, and this body starts at "name".
//
// **Embedded** (`subtreeRoot`, cr-node-key-hierarchy 5b.4b): core's folder body renders this one for a Memory or chapter
// BELOW a shared folder. It then shows that node — its name and key from the chain, its media and chapters only — and
// not the share's note (the folder body shows it once). The rights follow the link's role down (ADR 0080 Decision 6,
// 5a.5): a READ_WRITE guest adds photos here, every upload naming this node as its target; a READ guest only looks.
// The guest is named once per link, under the share ROOT's key (`nameShareGuest`) and only when it first adds a photo —
// no pre-screen in front of a node it browsed into; a session the folder body already saved is reused.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useConfirm } from '../../../shared/state/useConfirm'
import {
  listShareMedia,
  openSharedMemory,
  openShareGrid,
  openShareLightbox,
  openShareOriginal,
  shareChunkProvider,
  createContributorIdentity,
  sessionSealKey,
  addSharePhoto,
  deleteSharePhoto,
  type ShareItem,
  type ShareAccess,
  type ContributorSession,
} from '../share'
import { pickLocaleText, type PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { loadGuestSession, saveGuestSession, nameShareGuest } from '../../../core/sharing/guestIdentity'
import ShareNameDialog from '../../../core/sharing/ShareNameDialog.vue'
import { unsealMeta, originalBlobMeta, effectiveCapturedAt, hasDerivatives, downloadRef, type MediaItem } from '../media'
import { collectPickedFiles, inferMediaType, enqueueUploads, retryFile, removeFile } from '../uploadQueue'
import { useOptimisticTiles, type OptimisticTileVM } from '../useOptimisticTiles'
import { byCaptureTime } from '../optimisticUpload'
import { useThumbnailPipeline } from '../useThumbnailPipeline'
import { galleryDisplayAspectRatio } from '../mediaGridLayout'
import { loadOwnShareMedia, rememberOwnShareMedia, forgetOwnShareMedia } from '../ownShareMedia'
import { renderMarkdown } from '../../../shared/markdown'
import { ShareRole } from '../../../api/generated/sharing'
import MediaGrid from './MediaGrid.vue'
import MediaTile from './MediaTile.vue'
import OptimisticTile from './OptimisticTile.vue'
import MediaLightbox from './MediaLightbox.vue'

// The props are the registry's `ShareBodyProps` (core/applications/registry.ts), spelled out because the SFC compiler
// reads them statically.
const props = defineProps<{
  shareId: string
  fragmentKey: string
  pwToken: string | undefined
  head: PublicShareHead
  reload: () => Promise<void>
  subtreeRoot?: { nodeId: string; key: Uint8Array; name: string }
}>()
const emit = defineEmits<{ unavailable: [] }>()

const { t, locale } = useI18n()
const { ask } = useConfirm()

// A share's id never changes under a mounted body (the shell re-mounts per route).
const shareId = props.shareId
// Embedded for one node below the share root (see the header) — fixed for a mounted body: the folder body re-mounts per node.
const embeddedRoot = props.subtreeRoot
const memoryName = computed(() => embeddedRoot?.name ?? props.head.name)
// The upload group: the share, or the embedded node within it, so another Memory of the link shows none of its tiles.
const uploadGroup = embeddedRoot ? `${shareId}:${embeddedRoot.nodeId}` : shareId
// cr-0115: the optional curator note (a message the sharer attached), picked for the viewer's locale (cr-0116) and
// rendered as markdown (the safe escape-first subset — no raw HTML/script survives, so v-html is safe).
const note = computed(() => (embeddedRoot ? '' : pickLocaleText(props.head.noteByLocale, locale.value)))
const renderedNote = computed(() => (note.value ? renderMarkdown(note.value) : ''))

const items = ref<ShareItem[]>([])
// Whether the media listing has landed once: until then the page shows no count and no empty state, only the header
// (the listing is Memories' own read, after the shell's resolve — an empty Memory and one still loading are not the same).
const listed = ref(false)
// mediaId → ShareItem, so the injected lightbox loaders resolve each item's own K_mem (chapters differ).
const shareById = computed(() => new Map(items.value.map((si) => [si.item.mediaId, si])))
const listedMediaIds = computed(() => new Set(items.value.map((si) => si.item.mediaId)))
// Optimistic in-grid upload tiles (cr-0074) — the SAME shared composable as the internal gallery, grouped by
// this shareId: a contributor's picked photos appear instantly with a progress ring, reconciled once listed.
const { optimisticTiles, donorPreviewFor } = useOptimisticTiles(computed(() => uploadGroup), listedMediaIds)
// The thumbnail pipeline (cr-0150) — the SAME decrypt-per-tile → object-URL map as the internal gallery, here
// keyed on the share's per-item K_mem and gated presign (`openShareGrid`); the single revoke owner of every thumb.
const pipeline = useThumbnailPipeline<ShareItem>({
  idOf: (si) => si.item.mediaId,
  isDerivable: (si) => hasDerivatives(si.item),
  decryptTile: (si) => openShareGrid(shareId, si, props.pwToken),
  donorPreviewFor,
})
const thumbs = pipeline.thumbs // mediaId → thumbnail object URL

// --- justified grid tiles (fed to the shared MediaGrid) ---------------------
// Newest-first by effective capture time (undated last) — mirrors the curator gallery's default order, through
// the SAME comparator, so the two surfaces cannot drift into two orderings.
const capturedAtOf = (si: ShareItem) => effectiveCapturedAt(unsealMeta(si.scopeId, si.memoryKey, si.item))
const sortedItems = computed(() => byCaptureTime(items.value, capturedAtOf, -1))
const FALLBACK_ASPECT = galleryDisplayAspectRatio(undefined, undefined)
type ShareTileItem =
  | { id: string; aspectRatio: number; kind: 'optimistic'; optimistic: OptimisticTileVM }
  | { id: string; aspectRatio: number; kind: 'media'; si: ShareItem }
const mediaTile = (si: ShareItem): ShareTileItem => {
  const dims = unsealMeta(si.scopeId, si.memoryKey, si.item)
  return { id: si.item.mediaId, aspectRatio: galleryDisplayAspectRatio(dims?.w, dims?.h) || FALLBACK_ASPECT, kind: 'media', si }
}
// Grouped by chapter (inbox #356; ADR 0080 Decision 2 lets a guest read every chapter title): the shared node's own
// media first, under the page's heading, then one section per chapter titled as the member's sections are
// (`veyq-section`, its header and count), each a grid of its own. A chapter is keyed by its node — two may share a
// title — and the chapters come in the order of their newest media, so the grouping partitions the one newest-first
// order rather than inventing a second one. Nested chapters each get their own section. The guest's uploads land on the
// shared node itself, as they always did (no target picker is offered), so the in-flight tiles join its group.
// ONE order over both kinds of tile in that group (uc.memory.gallery § "One order for every tile") — a contributor's
// in-flight upload sorts on the capture time it will be sealed with, so settling changes its content, not its
// place. Optimistic tiles go in first so an equal key resolves in their favour: that is how a settling tile
// hands its own slot to the media it becomes.
type ShareGroup = { key: string; title: string | null; media: ShareItem[]; tiles: ShareTileItem[] }
const groups = computed<ShareGroup[]>(() => {
  const own: ShareItem[] = []
  const chapters = new Map<string, { title: string; media: ShareItem[] }>()
  for (const si of sortedItems.value) {
    if (si.chapterName === null) {
      own.push(si)
      continue
    }
    const group = chapters.get(si.nodeId) ?? { title: si.chapterName, media: [] }
    group.media.push(si)
    chapters.set(si.nodeId, group)
  }
  const ownTiles = byCaptureTime<ShareTileItem>(
    [
      ...optimisticTiles.value.map((t) => ({ id: t.layoutId, aspectRatio: t.aspectRatio, kind: 'optimistic' as const, optimistic: t })),
      ...own.map(mediaTile),
    ],
    (tile) => (tile.kind === 'optimistic' ? tile.optimistic.capturedAt : capturedAtOf(tile.si)),
    -1,
  )
  return [
    ...(ownTiles.length ? [{ key: 'own', title: null, media: own, tiles: ownTiles }] : []),
    ...[...chapters].map(([nodeId, g]) => ({ key: nodeId, title: g.title, media: g.media, tiles: g.media.map(mediaTile) })),
  ]
})
// The LISTED media in the order the page shows them — the lightbox's world: a tile's `openLightboxAt(displayOrder
// .indexOf(…))` is a position in THIS list, never in a merged one, so an in-flight tile cannot shift which photo opens,
// and the lightbox steps through the groups as the page lays them out.
const displayOrder = computed(() => groups.value.flatMap((g) => g.media))
// Show the grid when there's anything to render — listed media OR an in-flight optimistic upload.
const hasContent = computed(() => items.value.length > 0 || optimisticTiles.value.length > 0)

// --- contributor / uploader (READ_WRITE + WRITE_ONLY shares, cr-0101 / cr-0102) ----------------------------
// The share role + what its link lets this guest hold (ADR 0031 point 7): the root Memory's K_mem for a viewer or
// contributor, only the Memory's drop public key for an uploader — which never holds K_mem.
const role = computed<ShareRole>(() => props.head.role)
const access = ref<ShareAccess | null>(null)
// The shared Memory's scope — a contributor's name and uploads are sealed bound to it (ADR 0072).
const shareScopeId = computed(() => props.head.scopeId)
const isContributor = computed(() => role.value === ShareRole.ReadWrite)
// WRITE_ONLY (cr-0102, ADR 0031): a write-only drop-box — add photos but the resolve serves no media. The own-view
// is the optimistic tiles themselves (local previews), session-only: on reload the in-memory queue is empty.
const isUploader = computed(() => role.value === ShareRole.WriteOnly)
// Either contributing role names itself once, then may add photos (the backend accepts both on the upload path).
const canContribute = computed(() => isContributor.value || isUploader.value)
// The named contributor session, persisted per-share so a reload keeps the name (never leaves the device).
// The platform keeps it (`core/sharing/guestIdentity.ts`), one per link whichever body named the guest.
const session = ref<ContributorSession | null>(loadGuestSession<ContributorSession>(shareId)) // before the first render: the shell mounts this body once the share is ready, so no name-gate flash
// The key this session's uploads seal under — K_mem for a contributor, the uploader's own key for an uploader.
const sealKey = computed(() => (session.value && access.value ? sessionSealKey(session.value, access.value) : null))
// A READ_WRITE or WRITE_ONLY share shows a mandatory name pre-screen until the visitor has named themselves — and
// again for an uploader session saved without its own key, which has nothing to seal with.
// Embedded, the guest is asked at its first photo instead (`askName`), never by a pre-screen.
const needsName = computed(() => !embeddedRoot && canContribute.value && !sealKey.value)
const askName = ref(false)
async function nameEmbeddedGuest(name: string) {
  session.value = await nameShareGuest(props.head, { name, fragmentKey: props.fragmentKey, pwToken: props.pwToken })
}
const displayName = ref('')
const naming = ref(false)
const nameError = ref(false)
async function submitName() {
  const name = displayName.value.trim()
  if (!name || naming.value || !access.value) return
  naming.value = true
  nameError.value = false
  try {
    const created = await createContributorIdentity(shareId, shareScopeId.value, access.value, name, props.fragmentKey, props.pwToken)
    saveGuestSession(shareId, created)
    session.value = created
  } catch {
    nameError.value = true
  } finally {
    naming.value = false
  }
}

// Delete-your-own (READ_WRITE, inbox #361): the listed media this device added under the link's identity — the server
// tells no guest which uploads are whose (ADR 0030 point 8), so the device remembers its own (`ownShareMedia.ts`). Each
// of those tiles carries a delete, asked first; the server deletes it only when its identity is the token's, wherever
// below the share it lies, so the record can offer no more than the server grants. A WRITE_ONLY guest keeps its
// session-only delete on the in-flight tiles below — it is served no listed media to carry one.
const ownMediaIds = ref<Set<string>>(new Set())
watch(
  session,
  (s) => {
    ownMediaIds.value = s && isContributor.value ? loadOwnShareMedia(shareId, s.identityId) : new Set()
  },
  { immediate: true },
)
const isOwn = (si: ShareItem) => ownMediaIds.value.has(si.item.mediaId)
const deletingMediaIds = ref<Set<string>>(new Set())

// Add photos (contributor): enqueue the picked files on the SHARED upload queue with a share-scoped transport —
// they render as optimistic tiles (progress/retry) immediately and reconcile into the gallery on completion.
// The pick runs through `collectPickedFiles`, the same uc.memory.upload E3 window the curator's picker uses: a
// visitor importing from Safari's Photos gallery hits the identical late `input.files` population, and reading
// the input once would drop whatever had not arrived yet — silently, and a contributed photo is not ours to lose.
const fileInput = ref<HTMLInputElement | null>(null)
let cancelPickWindow: (() => void) | null = null
function triggerAddPhotos() {
  if (embeddedRoot && !sealKey.value) {
    session.value ??= loadGuestSession<ContributorSession>(shareId) // the folder body may have named the guest since
    if (!sealKey.value) {
      askName.value = true
      return
    }
  }
  fileInput.value?.click()
}
function onFilesPicked() {
  const input = fileInput.value
  if (!input) return
  cancelPickWindow?.() // a fresh pick supersedes whatever the previous window was still waiting for
  cancelPickWindow = collectPickedFiles(input, enqueueBatch)
}
function enqueueBatch(picked: File[]) {
  const s = session.value
  const key = sealKey.value
  const scopeId = shareScopeId.value
  if (!s || !key) return
  enqueueUploads(
    {
      key: uploadGroup,
      // Embedded: the target is this node (5a.5); at the root no target is named, as before.
      upload: async (source, opts) => {
        const added = await addSharePhoto(
          { shareId, scopeId, sealKey: key, ...s, pwToken: props.pwToken, ...(embeddedRoot ? { nodeId: embeddedRoot.nodeId } : {}) }, source, undefined, opts,
        )
        if (isContributor.value) {
          rememberOwnShareMedia(shareId, s.identityId, added.mediaId) // the device's record of what it may delete (#361)
          ownMediaIds.value = new Set(ownMediaIds.value).add(added.mediaId)
        }
        return added
      },
    },
    picked.map((file) => ({ file, mediaType: inferMediaType(file) })),
    // READ_WRITE: re-resolve so the contributed photo lists. WRITE_ONLY: never re-resolve (the resolve serves the
    // uploader no media) — the done optimistic tile stays as the session-only own-view from its local preview.
    { label: memoryName.value, onComplete: isUploader.value ? undefined : () => void props.reload() },
  )
}

// Delete-your-own (WRITE_ONLY, cr-0102): the settled tile carries the returned mediaId — call the token-gated
// delete endpoint, then drop the file from the queue (its optimistic tile + local preview go). Session-only: after
// a reload the client no longer holds the id, so delete is reachable only this session.
const deletingIds = ref<Set<string>>(new Set())
const deleteError = ref(false)
async function onDeleteUpload(fileId: string) {
  const tile = optimisticTiles.value.find((t) => t.fileId === fileId)
  const mediaId = tile?.mediaId
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

// A READ_WRITE guest's own listed photo (#361): asked first — the delete is for everyone and keeps no copy — then gone
// from the page at once (the listing would agree; no re-resolve is needed for a removal).
async function onDeleteOwn(si: ShareItem) {
  const s = session.value
  const mediaId = si.item.mediaId
  if (!s || deletingMediaIds.value.has(mediaId)) return
  const confirmed = await ask({
    header: t('share.deletePhoto'),
    message: t('share.deleteOwnConfirm'),
    acceptLabel: t('share.deleteOwnAccept'),
    acceptIcon: 'pi pi-trash',
  })
  if (!confirmed) return
  deleteError.value = false
  deletingMediaIds.value = new Set(deletingMediaIds.value).add(mediaId)
  try {
    await deleteSharePhoto(shareId, s.contributorToken, mediaId, props.pwToken)
    forgetOwnShareMedia(shareId, s.identityId, mediaId)
    const own = new Set(ownMediaIds.value)
    own.delete(mediaId)
    ownMediaIds.value = own
    const rest = items.value.filter((x) => x.item.mediaId !== mediaId)
    pipeline.reconcile(rest) // drops the removed tile's thumbnail
    items.value = rest
  } catch {
    deleteError.value = true // the tile stays, so the guest can try again
  } finally {
    const next = new Set(deletingMediaIds.value)
    next.delete(mediaId)
    deletingMediaIds.value = next
  }
}

// --- lightbox (the SHARED MediaLightbox in readonly mode, share loaders injected) ---------------------------
const lightboxIndex = ref<number | null>(null)
const lightboxItems = ref<ShareItem[]>([])
const lightboxMediaItems = computed(() => lightboxItems.value.map((s) => s.item))
function openLightboxAt(index: number) {
  lightboxItems.value = displayOrder.value.slice()
  lightboxIndex.value = index
}
// Injected operator-blind loaders — each resolves the media's ShareItem (its own K_mem) then goes through the
// gated share-scoped presign endpoint (no auth), so the shared lightbox drives the anonymous viewer unchanged.
// The shared lightbox requires a `memoryKey` prop, but the share viewer overrides every use of it via the
// injected loaders/metaResolver (each item carries its own K_mem), so a dummy empty key is passed + never used.
const unusedMemoryKey = new Uint8Array()
const previewLoader = (item: MediaItem) => openShareLightbox(shareId, shareById.value.get(item.mediaId)!, props.pwToken)
const originalLoader = (item: MediaItem) => openShareOriginal(shareId, shareById.value.get(item.mediaId)!, props.pwToken)
const metaResolver = (item: MediaItem) => {
  const si = shareById.value.get(item.mediaId)!
  return unsealMeta(si.scopeId, si.memoryKey, item)
}
// The original's sealed blob meta (inbox #397) — its name and type — under the item's own K_mem, as the tier above.
const blobMetaResolver = (item: MediaItem) => {
  const si = shareById.value.get(item.mediaId)!
  return originalBlobMeta(si.scopeId, si.memoryKey, item)
}
// Slice 2 (ADR 0047): the share-scoped key-free chunk provider over the original, for the SW range path and the
// lightbox's streamed download (inbox #371) — the anonymous viewer's per-item K_mem + guest pwToken, over the gated
// share presign. The SW never learns it is the share route.
const videoChunkProvider = (item: MediaItem) =>
  shareChunkProvider(shareId, shareById.value.get(item.mediaId)!, downloadRef(item), props.pwToken)

// --- open the Memory's seals from the shell's head (on mount, and again after every re-resolve) -----------
// The keys open synchronously from the head, so the first render already knows what the link lets this guest hold (a
// named contributor sees no name-gate flash). The media are Memories' own read (`listShareMedia`), started before the
// keys are opened and merged when it lands; a re-resolve after a contribution replaces `head` and lands here again (an
// incremental merge: the pipeline drops thumbs of removed media and donates a just-uploaded photo's optimistic preview,
// then decrypts each derivable item's real thumbnail). A listing overtaken by a newer head is dropped.
watch(
  () => props.head,
  async (head) => {
    const listing = listShareMedia(shareId, head.role, props.pwToken)
    listing.catch(() => {}) // awaited below, unless the keys fail to open first
    let opened
    try {
      opened = openSharedMemory(head, undefined, embeddedRoot)
    } catch {
      emit('unavailable') // a response the fragment key does not open: the shell shows its honest unavailable state
      return
    }
    access.value = opened.access
    let withMedia
    try {
      withMedia = openSharedMemory(head, await listing, embeddedRoot)
    } catch {
      if (props.head === head) emit('unavailable') // the link went away between its resolve and this read
      return
    }
    if (props.head !== head) return
    pipeline.reconcile(withMedia.items)
    items.value = withMedia.items
    listed.value = true
    void pipeline.decrypt(withMedia.items)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  pipeline.revokeAll()
  cancelPickWindow?.() // the E3 re-read window owns its teardown — no timer outlives the view
})
</script>

<template>
  <!-- READ_WRITE / WRITE_ONLY share: a mandatory display-name pre-screen before contributing (ADR 0030 / 0031) -->
  <div v-if="needsName" class="share-view-state share-name-gate">
    <form class="veyq-surface share-name-card" @submit.prevent="submitName">
      <h1>{{ memoryName }}</h1>
      <p class="share-name-intro">{{ isUploader ? t('share.uploaderIntro') : t('share.contributeIntro') }}</p>
      <!-- cr-0115: the curator's note = the drop-box instruction ("please upload here"). renderMarkdown escapes
           first (safe subset), so v-html is safe. -->
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div v-if="renderedNote" class="share-note markdown-body" v-html="renderedNote"></div>
      <!-- warning #1 of 4 (ADR 0031 §5): the session-only ephemerality, up front before the first upload -->
      <p v-if="isUploader" class="share-uploader-warning">
        <span class="pi pi-info-circle" />
        <span>{{ t('share.uploaderEphemeralWarning') }}</span>
      </p>
      <label for="share-name-input">{{ t('share.yourName') }}</label>
      <input
        id="share-name-input"
        v-model="displayName"
        type="text"
        name="display-name"
        autocomplete="name"
        :placeholder="t('share.yourNamePlaceholder')"
        :disabled="naming"
      />
      <!-- who reads the name: the Memory's members, never another link holder (ADR 0030 point 8) -->
      <p class="share-name-intro">{{ t('share.nameVisibility') }}</p>
      <button class="share-name-submit" type="submit" :disabled="naming || !displayName.trim()">
        {{ naming ? t('share.joining') : t('share.join') }}
      </button>
      <p v-if="nameError" class="share-error">{{ t('share.joinFailed') }}</p>
    </form>
  </div>

  <template v-else>
    <header class="gallery-header share-view-header">
      <div class="gallery-header-copy">
        <h1 class="gallery-title">{{ memoryName }}</h1>
        <span v-if="!isUploader && listed" class="gallery-count">{{ t('media.itemCount', { count: items.length }, items.length) }}</span>
        <span
          class="share-view-badge"
          :class="{ 'share-view-badge-uploader': isUploader }"
        >
          <span :class="isUploader ? 'pi pi-cloud-upload' : 'pi pi-share-alt'" />
          {{ isUploader ? t('share.uploaderBadge') : isContributor ? t('share.contributorBadge') : t('share.readonly') }}
        </span>
      </div>
      <div v-if="canContribute" class="share-view-actions">
        <button type="button" class="share-add-button" @click="triggerAddPhotos">
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

    <!-- cr-0115: the curator's note, a banner under the header for a READ/READ_WRITE share. Safe markdown
         (escape-first), so v-html is safe. -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-if="renderedNote" class="share-note markdown-body" v-html="renderedNote"></div>

    <!-- warning #4 of 4 (ADR 0031 §5): a persistent note that the own-view is session-only + delete-in-session -->
    <p v-if="isUploader && hasContent" class="share-uploader-note">
      <span class="pi pi-info-circle" />
      <span>{{ t('share.uploaderSessionNote') }}</span>
    </p>
    <p v-if="deleteError" class="share-error">{{ t('share.deleteFailed') }}</p>

    <!-- warning #3 of 4: the reload empty-state — after a reload the session own-view is gone (not a blank grid) -->
    <p v-if="!hasContent && (listed || isUploader)" class="veyq-empty">
      {{ isUploader ? t('share.uploaderEmpty') : isContributor ? t('share.emptyContribute') : t('share.empty') }}
    </p>
    <template v-else-if="hasContent">
      <!-- One grid per group (#356): the shared node's own media under the page heading, then each chapter as a section
           titled like the member's sections. -->
      <section
        v-for="group in groups"
        :key="group.key"
        class="share-media-group"
        :class="{ 'veyq-section': group.title !== null }"
        :data-chapter-id="group.title !== null ? group.key : undefined"
      >
        <div v-if="group.title !== null" class="veyq-section-header">
          <h2>{{ group.title }}</h2>
          <span>{{ group.media.length }}</span>
        </div>
        <MediaGrid :items="group.tiles" :aria-label="group.title ?? memoryName">
          <template #tile="{ tile, item }">
            <!-- optimistic in-grid upload tile (cr-0074): the SAME shared OptimisticTile as the internal gallery.
                 For a WRITE_ONLY share the settled tile persists as the session-only own-view + carries a per-tile delete. -->
            <OptimisticTile
              v-if="item.kind === 'optimistic'"
              :tile="item.optimistic"
              :deletable="isUploader"
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
            <!-- The member gallery's tile (#361): one open button, and on a photo this guest added, a delete beside it. -->
            <MediaTile
              v-else
              :data-media-id="item.si.item.mediaId"
              :style="{
                transform: `translate(${tile.left}px, ${tile.top}px)`,
                width: `${tile.renderedWidth}px`,
                height: `${tile.renderedHeight}px`,
              }"
              :src="thumbs.get(item.si.item.mediaId)"
              :has-derivatives="hasDerivatives(item.si.item)"
              :open-label="t('media.openItem', { index: displayOrder.indexOf(item.si) + 1 })"
              @open="openLightboxAt(displayOrder.indexOf(item.si))"
            >
              <template v-if="isOwn(item.si)" #overlay>
                <button
                  type="button"
                  class="share-own-delete"
                  :disabled="deletingMediaIds.has(item.si.item.mediaId)"
                  :aria-label="t('share.deletePhoto')"
                  :title="t('share.deletePhoto')"
                  @click.stop="onDeleteOwn(item.si)"
                >
                  <span :class="deletingMediaIds.has(item.si.item.mediaId) ? 'pi pi-spin pi-spinner' : 'pi pi-trash'" aria-hidden="true" />
                </button>
              </template>
            </MediaTile>
          </template>
        </MediaGrid>
      </section>
    </template>
  </template>

  <!-- The SAME lightbox component as the internal Memory, driven read-only with the anonymous share loaders. -->
  <MediaLightbox
    v-if="lightboxIndex !== null"
    :scope-id="shareId"
    :memory-key="unusedMemoryKey"
    :items="lightboxMediaItems"
    :start-index="lightboxIndex"
    :memory-name="memoryName"
    readonly
    :preview-loader="previewLoader"
    :original-loader="originalLoader"
    :meta-resolver="metaResolver"
    :blob-meta-resolver="blobMetaResolver"
    :video-chunk-provider="videoChunkProvider"
    @close="lightboxIndex = null"
  />

  <!-- Embedded: the guest names itself once per link, at its first photo (the folder body's dialog). -->
  <ShareNameDialog
    v-if="askName"
    :title="t('share.folder.guestNameTitle')"
    :label="t('share.yourName')"
    :submit-label="t('share.join')"
    :hint="t('share.folder.guestNameHint')"
    :action="nameEmbeddedGuest"
    :error-text="() => t('share.joinFailed')"
    @close="askName = false"
  />
</template>

<style scoped src="../../../shared/ui/shareGate.css"></style>

<style scoped>
/* The Memories body of the share page. The state/card look (name pre-screen) comes from the shared gate sheet
   above; the gallery grid (.media-grid / .gallery-tile) + the lightbox skin come from the GLOBAL design-source
   stylesheets (styles/memory.css + media.css) via the shared components, so a share looks exactly like the internal
   Memory. */
/* The header reuses the design-source gallery-header look (title + count); contributor shares add an action. */
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
.share-add-button:disabled {
  opacity: 0.6;
  cursor: default;
}
.share-view-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  margin-top: 0.15rem;
  color: var(--app-muted);
  font-size: 0.82rem;
  font-weight: 600;
}
.share-view-badge .pi {
  font-size: 0.85rem;
}
/* The uploader badge is a persistent warning tone (session-only own-view), not the neutral read-only badge. */
.share-view-badge-uploader {
  color: var(--app-warning-text);
  font-weight: 700;
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
/* cr-0115: the curator note — a card banner (in the header flow) / instruction (in the name pre-screen). The
   inner elements come from the safe markdown renderer via v-html, so they need :deep. */
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
.share-note :deep(h1) {
  font-size: 1.15rem;
  margin: 0 0 0.5rem;
}
.share-note :deep(h2) {
  font-size: 1rem;
  margin: 0.9rem 0 0.35rem;
}
.share-note :deep(h3) {
  font-size: 0.92rem;
  margin: 0.8rem 0 0.3rem;
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
/* The tiles are the member gallery's MediaTile and OptimisticTile, styled by themselves. Between the groups (the shared
   node's own media, then one section per chapter, #356) the page's section rhythm. */
.share-media-group + .share-media-group {
  margin-top: 1.6rem;
}
/* A guest's delete on its own photo (#361): the tile's mark toggle's look (MediaTile), at the opposite corner — a
   surface scrim legible over any photo in either theme; the danger tone on hover, as the uploader tile's delete. */
.share-own-delete {
  position: absolute;
  top: 4px;
  right: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-surface) 70%, transparent);
  color: var(--app-text);
  font-size: 0.85rem;
  cursor: pointer;
  backdrop-filter: blur(2px);
}
.share-own-delete:hover {
  background: var(--app-danger);
  color: var(--app-on-fill);
}
.share-own-delete:disabled {
  opacity: 0.6;
  cursor: default;
}
.share-own-delete:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
</style>
