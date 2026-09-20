<script setup lang="ts">
// The anonymous public viewer for a shared Memory (uc.sharing.share-public-viewer, cr-0100, ADR 0029). Mounted
// at the no-session route `/s/:shareId`; the share key `K_share` rides the URL **#fragment** (`#k=<base64url>`)
// and is read on device — it never reaches the server. We resolve the share (unauthenticated), unwrap `K_mem`
// with the fragment key, decrypt names/metadata/media **locally**, and render the Memory **read-only** using the
// SAME components as the internal Memory — the shared [MediaGrid] (justified layout) + [MediaLightbox] (in
// `readonly` mode, with the operator-blind per-item share byte-loaders injected) — so a share looks exactly like
// the Memory it mirrors, minus the curator-only affordances. A footer links to veyq's security docs (the
// client-integrity trust tier, ADR 0026). Operator-blind: media bytes are fetched direct-from-store via gated
// presigned GETs, so the operator never learns which media a viewer opens.
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  resolveShare,
  openShareGrid,
  openShareLightbox,
  openShareOriginal,
  shareChunkProvider,
  createContributorIdentity,
  addSharePhoto,
  deleteSharePhoto,
  pickLocaleText,
  derivePasswordToken,
  fetchShareTerms,
  pickShareTerms,
  acceptedTermsVersion,
  recordTermsAccepted,
  SharePasswordRequiredError,
  SharePasswordIncorrectError,
  type ShareItem,
  type ContributorSession,
} from '../features/memory/share'
import { unsealMeta, effectiveCapturedAt, hasDerivatives, downloadRef, type MediaItem } from '../features/memory/media'
import { isSupportedMediaFile, inferMediaType, enqueueUploads, retryFile, removeFile } from '../features/memory/uploadQueue'
import { useOptimisticTiles, type OptimisticTileVM } from '../features/memory/useOptimisticTiles'
import { byCaptureTime } from '../features/memory/optimisticUpload'
import { useThumbnailPipeline } from '../features/memory/useThumbnailPipeline'
import { galleryDisplayAspectRatio } from '../features/memory/mediaGridLayout'
import { renderMarkdown } from '../shared/markdown'
import { ShareRole } from '../api/generated/sharing'
import MediaGrid from '../features/memory/components/MediaGrid.vue'
import OptimisticTile from '../features/memory/components/OptimisticTile.vue'
import MediaLightbox from '../features/memory/components/MediaLightbox.vue'

const route = useRoute()
const { t, locale } = useI18n()

const shareId = String(route.params.shareId ?? '')
// The fragment key rides `#k=<base64url>` (ADR 0015) — read on device, never sent. Router history strips it from
// route.hash into `#k=...`; parse it here (empty if absent → an inert link, handled as unavailable).
const fragmentKey = computed(() => {
  const h = route.hash.startsWith('#') ? route.hash.slice(1) : route.hash
  const m = /(?:^|&)k=([^&]+)/.exec(h)
  return m ? m[1] : ''
})

const status = ref<'loading' | 'ready' | 'unavailable' | 'locked'>('loading')
// cr-0118: a password-protected share resolves to a LOCKED shell — the viewer enters the password, we derive the
// Argon2id token client-side, and re-resolve with it. The token is then held + presented on every gated blob GET.
const pwSalt = ref('')
const pwInput = ref('')
const pwToken = ref<string | undefined>(undefined)
const pwError = ref(false) // last attempt was the wrong password
const pwBusy = ref(false) // deriving Argon2id + re-resolving
const memoryName = ref('')
// cr-0115: the optional curator note (a message the sharer attached), decrypted client-side + rendered as markdown
// (the safe escape-first subset — no raw HTML/script survives, so v-html is safe).
const note = ref('')
const renderedNote = computed(() => (note.value ? renderMarkdown(note.value) : ''))

// --- accountless terms gate (uc.sharing.share-accept, ADR 0035 §3) ----------
// Before viewing/contributing, the visitor accepts the operator's tenant terms. Acceptance is stored CLIENT-SIDE
// only (localStorage, keyed per tenant) — the server records nothing about the accountless visitor. This is an
// OPERATIONAL GATE, NOT ENFORCEMENT: a localStorage flag is bypassable, so it records + surfaces consent, it does
// not police access (the terms' content + the decryptable-on-report path are the operator's real remedy). It also
// FAILS OPEN: a transient terms-fetch error is treated as "no terms" (version 0) so a network blip never locks a
// visitor out. The terms come from the share (GET /api/shares/{shareId}/terms) — a public share is Host-independent,
// so the tenant can't come from the Host, and the member terms endpoint is auth+membership-gated.
const termsTenantId = ref('')
const termsVersion = ref(0) // 0 = no gate (also the fail-open value on a fetch error)
const acceptedVersion = ref(0) // the locally-recorded accepted version; bumped in-memory on accept so the gate clears
const termsMarkdown = ref('')
const renderedTerms = computed(() => (termsMarkdown.value ? renderMarkdown(termsMarkdown.value) : ''))
// The gate fires once the share is resolved (post-password), the tenant has published terms, and the locally-stored
// accepted version is below current. Sits BETWEEN the password shell and the display-name pre-screen in the template.
const needsTerms = computed(() => status.value === 'ready' && termsVersion.value > 0 && acceptedVersion.value < termsVersion.value)

/** Fetch the owning tenant's terms + read the locally-stored accepted version. FAILS OPEN (uc.sharing.share-accept):
 *  a transient error is treated as "no terms" (version 0) so the visitor is never locked out by a network blip. */
async function loadTerms() {
  try {
    const terms = await fetchShareTerms(shareId)
    termsTenantId.value = terms.tenantId
    termsVersion.value = terms.termsVersion
    termsMarkdown.value = pickShareTerms(terms, locale.value)
    acceptedVersion.value = acceptedTermsVersion(terms.tenantId)
  } catch {
    termsVersion.value = 0
    acceptedVersion.value = 0
  }
}

/** Record acceptance client-side (localStorage, per tenant) + clear the gate in-memory. No server call — the
 *  accountless visitor's acceptance is client-only, so the server keeps no record (anonymity preserved). */
function acceptTerms() {
  recordTermsAccepted(termsTenantId.value, termsVersion.value)
  acceptedVersion.value = termsVersion.value
}

const items = ref<ShareItem[]>([])
// mediaId → ShareItem, so the injected lightbox loaders resolve each item's own K_mem (chapters differ).
const shareById = computed(() => new Map(items.value.map((si) => [si.item.mediaId, si])))
const listedMediaIds = computed(() => new Set(items.value.map((si) => si.item.mediaId)))
// Optimistic in-grid upload tiles (cr-0074) — the SAME shared composable as the internal gallery, grouped by
// this shareId: a contributor's picked photos appear instantly with a progress ring, reconciled once listed.
const { optimisticTiles, donorPreviewFor } = useOptimisticTiles(computed(() => shareId), listedMediaIds)
// The thumbnail pipeline (cr-0150) — the SAME decrypt-per-tile → object-URL map as the internal gallery, here
// keyed on the share's per-item K_mem and gated presign (`openShareGrid`); the single revoke owner of every thumb.
const pipeline = useThumbnailPipeline<ShareItem>({
  idOf: (si) => si.item.mediaId,
  isDerivable: (si) => hasDerivatives(si.item),
  decryptTile: (si) => openShareGrid(shareId, si, pwToken.value),
  donorPreviewFor,
})
const thumbs = pipeline.thumbs // mediaId → thumbnail object URL

// The security-doc footer link (ADR 0026 trust-tier honesty) — the published wiki, locale-aware.
const securityDocUrl = computed(() =>
  locale.value === 'de'
    ? 'https://github.com/raddatzk/veyq/wiki/sind-meine-daten-sicher'
    : 'https://github.com/raddatzk/veyq/wiki/is-my-data-safe',
)

// --- justified grid tiles (fed to the shared MediaGrid) ---------------------
// Newest-first by effective capture time (undated last) — mirrors the curator gallery's default order, through
// the SAME comparator, so the two surfaces cannot drift into two orderings.
const capturedAtOf = (si: ShareItem) => effectiveCapturedAt(unsealMeta(si.memoryKey, si.item))
// The LISTED media in grid order — the lightbox's world: the tile's `openLightboxAt(sortedItems.indexOf(…))`
// is a position in THIS list, never in the merged one, so an in-flight tile cannot shift which photo opens.
const sortedItems = computed(() => byCaptureTime(items.value, capturedAtOf, -1))
const FALLBACK_ASPECT = galleryDisplayAspectRatio(undefined, undefined)
type ShareTileItem =
  | { id: string; aspectRatio: number; kind: 'optimistic'; optimistic: OptimisticTileVM }
  | { id: string; aspectRatio: number; kind: 'media'; si: ShareItem }
// ONE order over both kinds of tile (uc.memory.gallery § "One order for every tile") — a contributor's
// in-flight upload sorts on the capture time it will be sealed with, so settling changes its content, not its
// place. Optimistic tiles go in first so an equal key resolves in their favour: that is how a settling tile
// hands its own slot to the media it becomes.
const layoutItems = computed<ShareTileItem[]>(() =>
  byCaptureTime<ShareTileItem>(
    [
      ...optimisticTiles.value.map((t) => ({ id: t.layoutId, aspectRatio: t.aspectRatio, kind: 'optimistic' as const, optimistic: t })),
      ...sortedItems.value.map((si) => {
        const dims = unsealMeta(si.memoryKey, si.item)
        return { id: si.item.mediaId, aspectRatio: galleryDisplayAspectRatio(dims?.w, dims?.h) || FALLBACK_ASPECT, kind: 'media' as const, si }
      }),
    ],
    (tile) => (tile.kind === 'optimistic' ? tile.optimistic.capturedAt : capturedAtOf(tile.si)),
    -1,
  ),
)
// Show the grid when there's anything to render — listed media OR an in-flight optimistic upload.
const hasContent = computed(() => items.value.length > 0 || optimisticTiles.value.length > 0)


// --- contributor / uploader (CONTRIBUTOR + UPLOADER shares, cr-0101 / cr-0102) ----------------------------
// The share role + the root Memory's K_mem (for a contributor/uploader to seal their name + uploads under).
const role = ref<ShareRole>(ShareRole.Viewer)
const rootMemoryKey = ref<Uint8Array | null>(null)
const isContributor = computed(() => role.value === ShareRole.Contributor)
// UPLOADER (cr-0102, ADR 0031): a write-only drop-box — add photos but the resolve serves no media. The own-view
// is the optimistic tiles themselves (local previews), session-only: on reload the in-memory queue is empty.
const isUploader = computed(() => role.value === ShareRole.Uploader)
// Either contributing role names itself once, then may add photos (the backend accepts both on the upload path).
const canContribute = computed(() => isContributor.value || isUploader.value)
// The named contributor session, persisted per-share so a reload keeps the name (never leaves the device).
const sessionKey = `veyq:share-contributor:${shareId}`
const session = ref<ContributorSession | null>(null)
function loadSession() {
  try {
    const raw = localStorage.getItem(sessionKey)
    session.value = raw ? (JSON.parse(raw) as ContributorSession) : null
  } catch {
    session.value = null
  }
}
// A CONTRIBUTOR or UPLOADER share shows a mandatory name pre-screen until the visitor has named themselves.
const needsName = computed(() => status.value === 'ready' && canContribute.value && !session.value)
const displayName = ref('')
const naming = ref(false)
const nameError = ref(false)
async function submitName() {
  const name = displayName.value.trim()
  if (!name || naming.value || !rootMemoryKey.value) return
  naming.value = true
  nameError.value = false
  try {
    const created = await createContributorIdentity(shareId, rootMemoryKey.value, name, pwToken.value)
    localStorage.setItem(sessionKey, JSON.stringify(created))
    session.value = created
  } catch {
    nameError.value = true
  } finally {
    naming.value = false
  }
}

// Add photos (contributor): enqueue the picked files on the SHARED upload queue with a share-scoped transport —
// they render as optimistic tiles (progress/retry) immediately and reconcile into the gallery on completion.
const fileInput = ref<HTMLInputElement | null>(null)
function triggerAddPhotos() {
  fileInput.value?.click()
}
function onFilesPicked() {
  const input = fileInput.value
  const picked = Array.from(input?.files ?? []).filter(isSupportedMediaFile)
  if (input) input.value = ''
  const s = session.value
  const key = rootMemoryKey.value
  if (!picked.length || !s || !key) return
  enqueueUploads(
    { key: shareId, upload: (source, opts) => addSharePhoto({ shareId, memoryKey: key, ...s, pwToken: pwToken.value }, source, undefined, opts) },
    picked.map((file) => ({ file, mediaType: inferMediaType(file) })),
    // CONTRIBUTOR: re-resolve so the contributed photo lists. UPLOADER: never re-resolve (the resolve serves the
    // uploader no media) — the done optimistic tile stays as the session-only own-view from its local preview.
    { label: memoryName.value, onComplete: isUploader.value ? undefined : () => void load() },
  )
}

// Delete-your-own (UPLOADER, cr-0102): the settled tile carries the returned mediaId — call the token-gated
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
    await deleteSharePhoto(shareId, s.contributorToken, mediaId, pwToken.value)
    removeFile(fileId)
  } catch {
    deleteError.value = true // keep the tile so the visitor can retry the delete this session
  } finally {
    const next = new Set(deletingIds.value)
    next.delete(fileId)
    deletingIds.value = next
  }
}

// --- lightbox (the SHARED MediaLightbox in readonly mode, share loaders injected) ---------------------------
const lightboxIndex = ref<number | null>(null)
const lightboxItems = ref<ShareItem[]>([])
const lightboxMediaItems = computed(() => lightboxItems.value.map((s) => s.item))
function openLightboxAt(index: number) {
  lightboxItems.value = sortedItems.value.slice()
  lightboxIndex.value = index
}
// Injected operator-blind loaders — each resolves the media's ShareItem (its own K_mem) then goes through the
// gated share-scoped presign endpoint (no auth), so the shared lightbox drives the anonymous viewer unchanged.
// The shared lightbox requires a `memoryKey` prop, but the share viewer overrides every use of it via the
// injected loaders/metaResolver (each item carries its own K_mem), so a dummy empty key is passed + never used.
const unusedMemoryKey = new Uint8Array()
const previewLoader = (item: MediaItem) => openShareLightbox(shareId, shareById.value.get(item.mediaId)!, pwToken.value)
const originalLoader = (item: MediaItem) => openShareOriginal(shareId, shareById.value.get(item.mediaId)!, pwToken.value)
const metaResolver = (item: MediaItem) => unsealMeta(shareById.value.get(item.mediaId)!.memoryKey, item)
// Slice 2 (ADR 0047): the share-scoped key-free chunk provider for the SW range path — the anonymous viewer's
// per-item K_mem + guest pwToken, over the gated share presign. The SW never learns it is the share route.
const videoChunkProvider = (item: MediaItem) =>
  shareChunkProvider(shareId, shareById.value.get(item.mediaId)!.memoryKey, downloadRef(item), pwToken.value)

// --- resolve (on mount + after a contribution) ------------------------------
async function load() {
  try {
    const resolved = await resolveShare(shareId, fragmentKey.value, pwToken.value)
    memoryName.value = resolved.memoryName
    // cr-0116: the curator note is a per-locale map — pick the viewer's locale (exact → base → en → first).
    note.value = pickLocaleText(resolved.noteByLocale, locale.value)
    role.value = resolved.role
    rootMemoryKey.value = resolved.rootMemoryKey
    // incremental merge (no empty-flash): the pipeline drops thumbs of removed media + donates a just-uploaded
    // photo's optimistic preview (hands off with no gray gap), then decrypts each derivable item's real thumbnail.
    // Decide the terms gate BEFORE flipping to 'ready' so an ungated content flash can't slip in between resolve
    // and the gate (loadTerms fails open, so it never throws here). The gate then sits ahead of the content.
    await loadTerms()
    pipeline.reconcile(resolved.items)
    items.value = resolved.items
    status.value = 'ready'
    await pipeline.decrypt(resolved.items)
  } catch (e) {
    // cr-0118: a password-protected share → prompt (or re-prompt on a wrong password); otherwise the honest
    // "unavailable" state (bad key / 404 / unexpected).
    if (e instanceof SharePasswordRequiredError) {
      pwSalt.value = e.pwSalt
      status.value = 'locked'
    } else if (e instanceof SharePasswordIncorrectError) {
      pwError.value = true
      status.value = 'locked'
    } else {
      status.value = 'unavailable'
    }
  }
}

/** The viewer submitted the password: derive the Argon2id token client-side, then re-resolve with it. */
async function submitPassword() {
  if (!pwInput.value || pwBusy.value) return
  pwBusy.value = true
  pwError.value = false
  await new Promise((r) => setTimeout(r, 10)) // let the busy state paint before the (blocking) Argon2id
  try {
    pwToken.value = derivePasswordToken(pwInput.value, pwSalt.value)
    status.value = 'loading'
    await load()
  } finally {
    pwBusy.value = false
  }
}
onMounted(() => {
  loadSession()
  void load()
})

onBeforeUnmount(() => pipeline.revokeAll())
</script>

<template>
  <div class="share-view">
    <div v-if="status === 'loading'" class="share-view-state">
      <span class="pi pi-spin pi-spinner" />
      <span>{{ t('share.loading') }}</span>
    </div>

    <div v-else-if="status === 'unavailable'" class="share-view-state share-view-unavailable">
      <span class="pi pi-lock" />
      <h1>{{ t('share.unavailable') }}</h1>
      <p>{{ t('share.unavailableHint') }}</p>
    </div>

    <!-- cr-0118: a password-protected share resolves to a LOCKED shell (nothing else crossed the wire) — prompt for
         the password, derive the Argon2id token on device, and re-resolve with it. -->
    <div v-else-if="status === 'locked'" class="share-view-state share-name-gate">
      <form class="veyq-surface share-name-card" @submit.prevent="submitPassword">
        <span class="pi pi-lock share-password-icon" />
        <h1>{{ t('share.passwordTitle') }}</h1>
        <p class="share-name-intro">{{ t('share.passwordIntro') }}</p>
        <label for="share-password-input">{{ t('share.passwordLabel') }}</label>
        <input
          id="share-password-input"
          v-model="pwInput"
          type="password"
          name="share-password"
          autocomplete="current-password"
          :placeholder="t('share.passwordPlaceholder')"
          :disabled="pwBusy"
        />
        <button class="share-name-submit" type="submit" :disabled="pwBusy || !pwInput">
          {{ pwBusy ? t('share.passwordChecking') : t('share.passwordUnlock') }}
        </button>
        <p v-if="pwError" class="share-error">{{ t('share.passwordWrong') }}</p>
      </form>
    </div>

    <!-- The accountless terms gate (uc.sharing.share-accept, ADR 0035 §3): the operator's terms + an Accept button,
         BETWEEN the password shell and the display-name pre-screen (password → terms → name → content). Acceptance
         is client-only (localStorage) — an operational gate, not enforcement: it records + surfaces consent, it does
         not police access. renderMarkdown escapes first (safe subset), so v-html is safe. -->
    <div v-else-if="needsTerms" class="share-view-state share-name-gate">
      <div class="veyq-surface share-name-card share-terms-card">
        <span class="pi pi-file-check share-terms-icon" />
        <h1>{{ t('share.termsTitle') }}</h1>
        <p class="share-name-intro">{{ t('share.termsIntro') }}</p>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div class="share-terms-body markdown-body" v-html="renderedTerms"></div>
        <button class="share-name-submit" type="button" @click="acceptTerms">{{ t('share.termsAccept') }}</button>
      </div>
    </div>

    <!-- CONTRIBUTOR / UPLOADER share: a mandatory display-name pre-screen before contributing (ADR 0030 / 0031) -->
    <div v-else-if="needsName" class="share-view-state share-name-gate">
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
          <span v-if="!isUploader" class="gallery-count">{{ t('media.itemCount', { count: items.length }, items.length) }}</span>
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

      <!-- cr-0115: the curator's note, a banner under the header for a VIEWER/CONTRIBUTOR share. Safe markdown
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
      <p v-if="!hasContent" class="veyq-empty">
        {{ isUploader ? t('share.uploaderEmpty') : isContributor ? t('share.emptyContribute') : t('share.empty') }}
      </p>
      <MediaGrid v-else :items="layoutItems" :aria-label="memoryName">
        <template #tile="{ tile, item }">
          <!-- optimistic in-grid upload tile (cr-0074): the SAME shared OptimisticTile as the internal gallery.
               For an UPLOADER the settled tile persists as the session-only own-view + carries a per-tile delete. -->
          <OptimisticTile
            v-if="item.kind === 'optimistic'"
            :tile="item.optimistic"
            :deletable="isUploader"
            :deleting="deletingIds.has(item.optimistic.fileId)"
            :style="{
              transform: `translate(${tile.left}px, ${tile.top}px)`,
              width: `${tile.renderedWidth}px`,
              height: `${tile.renderedHeight}px`,
            }"
            @retry="retryFile"
            @delete="onDeleteUpload"
          />
          <button
            v-else
            class="media-grid-tile"
            type="button"
            :data-media-id="item.si.item.mediaId"
            :style="{
              transform: `translate(${tile.left}px, ${tile.top}px)`,
              width: `${tile.renderedWidth}px`,
              height: `${tile.renderedHeight}px`,
            }"
            :aria-label="t('media.openItem', { index: sortedItems.indexOf(item.si) + 1 })"
            @click="openLightboxAt(sortedItems.indexOf(item.si))"
          >
            <img
              v-if="thumbs.get(item.si.item.mediaId)"
              class="media-grid-tile-media media-grid-tile-media-loaded"
              :src="thumbs.get(item.si.item.mediaId)"
              :alt="memoryName"
            />
            <span v-else-if="!hasDerivatives(item.si.item)" class="media-grid-icon pi pi-file" />
            <span v-else class="media-grid-tile-placeholder" />
          </button>
        </template>
      </MediaGrid>
    </template>

    <footer class="share-view-footer">
      <a :href="securityDocUrl" target="_blank" rel="noopener noreferrer">{{ t('share.securityFooter') }}</a>
    </footer>

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
      :video-chunk-provider="videoChunkProvider"
      @close="lightboxIndex = null"
    />
  </div>
</template>

<style scoped>
/* The share viewer fills the full viewport (rendered outside the app shell, in the 'plain' layout). Only
   layout-specific rules live here; the gallery grid (.media-grid / .gallery-tile) + the lightbox skin come from
   the GLOBAL design-source stylesheets (styles/memory.css + media.css) via the shared components, so a share
   looks exactly like the internal Memory. */
.share-view {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  width: min(1200px, 100%);
  margin: 0 auto;
  padding: 1.5rem 1.25rem 2rem;
  box-sizing: border-box;
}
.share-view-state {
  flex: 1;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 0.6rem;
  color: var(--app-muted);
  text-align: center;
}
.share-view-unavailable .pi {
  font-size: 2rem;
}
.share-view-unavailable h1 {
  margin: 0.25rem 0 0;
  font-size: 1.4rem;
}
.share-view-unavailable p {
  margin: 0;
  max-width: 30rem;
}
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
  color: var(--app-on-strong);
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
}
.share-add-button:disabled {
  opacity: 0.6;
  cursor: default;
}
/* The mandatory contributor name pre-screen — a centered card (design-source login-gate feel). */
.share-name-gate {
  flex: 1;
}
.share-name-card {
  display: grid;
  gap: 0.6rem;
  width: min(28rem, 100%);
  padding: 1.75rem;
  text-align: left;
}
.share-name-card h1 {
  margin: 0;
  font-size: 1.5rem;
}
.share-name-intro {
  margin: 0 0 0.4rem;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.share-name-card label {
  color: var(--app-muted);
  font-size: 0.82rem;
}
.share-name-card input {
  padding: 0.55rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
}
.share-name-submit {
  margin-top: 0.4rem;
  padding: 0.6rem 1rem;
  border: 0;
  border-radius: 8px;
  background: var(--app-accent);
  color: var(--app-on-strong);
  font-weight: 600;
  cursor: pointer;
}
.share-name-submit:disabled {
  opacity: 0.6;
  cursor: default;
}
.share-error {
  margin: 0.25rem 0 0;
  color: var(--app-danger);
  font-size: 0.85rem;
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
/* The terms gate reuses the centered card; its body is the safe-markdown terms, scrollable when long so the
   Accept button stays reachable. Wider than the name card since terms are prose. */
.share-terms-card {
  width: min(38rem, 100%);
}
.share-terms-icon {
  font-size: 1.5rem;
  color: var(--app-accent);
}
.share-terms-body {
  max-height: min(50vh, 26rem);
  overflow-y: auto;
  margin: 0.2rem 0 0.4rem;
  padding: 0.85rem 1rem;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-surface-muted);
  color: var(--app-text);
  font-size: 0.92rem;
  line-height: 1.55;
}
.share-terms-body :deep(h1) {
  font-size: 1.15rem;
  margin: 0 0 0.5rem;
}
.share-terms-body :deep(h2) {
  font-size: 1rem;
  margin: 0.9rem 0 0.35rem;
}
.share-terms-body :deep(h3) {
  font-size: 0.92rem;
  margin: 0.8rem 0 0.3rem;
}
.share-terms-body :deep(p) {
  margin: 0 0 0.55rem;
}
.share-terms-body :deep(ul),
.share-terms-body :deep(ol) {
  margin: 0 0 0.55rem;
  padding-left: 1.3rem;
}
.share-terms-body :deep(a) {
  color: var(--app-accent);
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
/* The tile (.media-grid-tile) + its inner classes are fully styled by the GLOBAL design-source stylesheet
   (styles/memory.css) — the same skin the internal gallery uses — so no per-view tile CSS is needed here. */
.share-view-footer {
  margin-top: 1.5rem;
  padding-top: 1rem;
  border-top: 1px solid var(--app-line);
  text-align: center;
}
.share-view-footer a {
  color: var(--app-muted);
  font-size: 0.82rem;
}
</style>
