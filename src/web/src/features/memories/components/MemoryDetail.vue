<script setup lang="ts">
// A Memory's detail surface = its **gallery** (cr-0049, uc.memory.gallery). Opens the Memory's media as a
// thumbnail grid, each tile's thumbnail fetched as ciphertext via presigned GET and decrypted on device under
// K_node (operator-blind); clicking a tile opens the lightbox (preview, prev/next, download original). The host
// (`MemoryDetailHost`) resolves the node's key from the tree, the pin and the item-mark key; the page around it owns the
// rename/delete/share/create dialogs and the navigation (every link through the tree's `linkTo`).
// Ported presentation from the design source (legacy/frontend-noe2ee); only the data layer is ours. Decrypted
// thumbnails become object URLs that are **revoked** on reload / unmount (no lingering plaintext blob URLs).
// Tabs: the Gallery, the Chapters (each opens to the same detail under its own `K_node`), the Contributions organizer
// (buckets) and the media Trash — the last two a member's only. Journal, tracks and playlists are not built.
//
// **The header is core's** (owner 2026-11-03, ADR 0110 decision 10): core's page draws it above this body, the cover
// included, and this view contributes data to it (`contributeNodeHeader`): the item count as the subtitle, its actions
// (upload, the share actions, the banner while none is shown, move, delete), the rename of its name in place, its facts -
// the date and the place, whose chips open this view's editors, and the participants (`MemoryParticipants`' fact, which opens
// their picker) - and the banner core draws above the row, which opens its own dialog where it may be changed. What is
// edited at its place has no action of its own (ADR 0110 decision 11).
//
// **One view for a member and a share guest** (inbox #401). It reads and writes only through `source` — Memories' data
// source (`../source.ts`), the member's or the guest's, fixed by the host at mount — and offers what `rights` grants
// (Memories' fail-closed matrix, `../rights.ts`) narrowed to the doors the source carries (`sourceRights`):
// `can`. Every control, tab and member-only effect — the item marks' load, the automatic date/place reconcile that
// writes the node's meta, the cover's save — reads `can`; nothing here checks a role, a pending deletion or an instance
// right itself, and nothing calls an API but through the source. The children that need the source get it by
// injection (`provideMemoryView`: the organizer). The delete differs honestly by door: a member's trash with undo, a
// guest's purge of its own media, asked first — on the tile and in the lightbox alike. The lightbox is handed the
// source's loaders and each write per right (`canDelete`, the source's `mediaMeta` door). A guest
// mounts this through core's share page (`core/sharing/ShareTreePage.vue`) for every Memory and chapter of its link: it
// sees the date and place sealed in the node's meta, but never reconciles them (that writes the node's meta, `editMeta`).
import { ref, computed, watch, nextTick, onBeforeUnmount, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqTabs from '../../../shared/ui/VeyqTabs.vue'
import VeyqTabList from '../../../shared/ui/VeyqTabList.vue'
import VeyqTab from '../../../shared/ui/VeyqTab.vue'
import VeyqTabPanels from '../../../shared/ui/VeyqTabPanels.vue'
import VeyqTabPanel from '../../../shared/ui/VeyqTabPanel.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import type { TreeNode } from '../../../core/node/tree'
import { shareOfferOf, sharesAny, type NodeShareOffer } from '../../../core/node/nodeHost'
import { hasDerivatives, mediaTypeOf, isVideoMediaType, type MediaItem } from '../media'
import { openedBlobMeta } from '../openedListing'
import { useMemoryDate } from '../useMemoryDate'
import { useMemoryLocation } from '../useMemoryLocation'
import { useMediaTrash } from '../useMediaTrash'
import MediaGrid from './MediaGrid.vue'
import MediaTile from './MediaTile.vue'
import MemoryBuckets from './MemoryBuckets.vue'
import OptimisticTile from './OptimisticTile.vue'
import { enqueueUploads, collectPickedFiles, inferMediaType, retryFile } from '../uploadQueue'
import { useMediaGalleryLive } from '../useMediaGalleryLive'
import { isMarkedByMe, markCount, markedByAnyone, clearItemFavorites } from '../itemFavoritesStore'
import { ItemMarksTooMany } from '../itemFavorites'
import MediaLightbox from './MediaLightbox.vue'
import MemoryDateEditor from './MemoryDateEditor.vue'
import MemoryLocationEditor from './MemoryLocationEditor.vue'
import MemoryParticipants from './MemoryParticipants.vue'
import { useUploaderNames } from '../useUploaderNames'
import MemoryCard from './MemoryCard.vue'
import { standardNodeActions, standardNodeRename, type NodeHeaderAction, type NodeHeaderFact } from '../../../core/node/nodeHeader'
import { contributeNodeHeader } from '../../../core/node/nodeHeaderContribution'
import { contributeSearchDocuments } from '../../../core/search/contribution'
import { searchOpenRequest, takeOpenRequest } from '../../../core/search/openRequest'
import { mediaDocuments } from '../search'
import ViewModeToggle from '../../../core/node/ViewModeToggle.vue'
import { GRID_VIEW, LIST_VIEW } from '../../../core/node/levelViews'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import { useViewMode } from '../../../shared/state/useViewMode'
import type { NodeMetaUpdate } from '../useMetaWriteFeedback'
import type { MemoryRights } from '../rights'
import { provideMemoryView, sourceRights, type MemorySource } from '../source'
import { useConfirm } from '../../../shared/state/useConfirm'
import { useToast } from '../../../shared/state/useToast'
import MemoryBanner from '../banner/MemoryBanner.vue'
import BannerDialog from '../banner/BannerDialog.vue'
import { useMemoryBanner } from '../banner/useMemoryBanner'
import { useBannerEditor } from '../banner/useBannerEditor'
import MapConsentRenewal from './MapConsentRenewal.vue'
import type { ScopeKey } from '../../spaces/epochs'

const props = defineProps<{
  node: TreeNode
  scopeId: string
  memoryKey: Uint8Array | null
  // The scope content key at the BASE epoch — what item marks seal under (ADR 0068). Not `memoryKey`: marks
  // belong to the scope's members, not to this Memory's content grain.
  scopeKey?: ScopeKey | null
  // A personal scope has nobody to share a mark with, so "everyone's / only mine" is degenerate there and the
  // filter is HIDDEN rather than rendered disabled (uc.memory.gallery E4).
  personalScope?: boolean
  chapters?: TreeNode[] // child CHAPTER nodes of this Memory/chapter (cr-0060)
  /** What the matrix grants whoever looks (see the header). Required: there is no default that grants anything. */
  rights: MemoryRights
  /** The session's data source (see the header). Required: there is no default door to fall back to. */
  source: MemorySource
  /** Core's cover dialog is open over this page (the host mounts it): the lightbox then leaves the keyboard to it. */
  coverEditing?: boolean
  /** The lightbox offers "Report" (uc.moderation.report): the host's answer, core's rule (`canReport`). */
  canReport?: boolean
  /** The page's rename in place of this node (its `renameTo` door); null where the page has none. */
  renameTo?: ((name: string) => Promise<void>) | null
}>()
const emit = defineEmits<{
  move: []
  delete: []
  openChapter: [node: TreeNode]
  createChapter: []
  share: [offer: NodeShareOffer]
  metaUpdated: [NodeMetaUpdate]
  /** The lightbox's "Report": the host opens core's report dialog for this medium. */
  reportMedium: [item: MediaItem]
}>()

const { t } = useI18n()
const { viewMode } = useViewMode()
const { ask, askWithOption } = useConfirm()
const toast = useToast()

/** What this view offers: the matrix's grant AND the source's door (see the header). */
const can = computed(() => sourceRights(props.rights, props.source))

// --- the banner (uc.memory.banner, ADR 0102): drawn above the header row from the node's own attachments, before and
// without the media list, the same for a member and a guest; set, re-framed or removed in its dialog where `setBanner`
// grants it. The dialog's save runs through the source's banner door (`useBannerEditor`).
const memoryBanner = useMemoryBanner({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  loaders: props.source.loaders,
})
/** The banner is on screen: set, and its picture opens (`MemoryBanner` draws nothing for one that does not). */
const bannerDrawn = computed(() => !!memoryBanner.banner.value && memoryBanner.status.value !== 'failed' && memoryBanner.status.value !== 'none')
/** Whether the banner dialog is open - the banner's only way in (a file picked there). */
const bannerDialog = ref(false)
function openBannerDialog() {
  if (!can.value.setBanner || !props.memoryKey) return
  bannerDialog.value = true
}
/**
 * Whether a medium is the banner's picture: its original's SHA-256 is the one the banner was made from (the two may share
 * one stored object, ADR 0013 § 5) - deleting it asks about the banner (ADR 0102 decision 7). A banner or a medium written
 * without a digest is no match.
 */
function isBannerPicture(mediaId: string): boolean {
  const digest = memoryBanner.banner.value?.sha256
  const item = items.value.find((i) => i.mediaId === mediaId)
  if (!digest || !item || !props.memoryKey) return false
  return props.source.loaders.blobMeta(item, props.memoryKey)?.sha256 === digest
}

// How far down the sticky stack reaches (the shell's chrome + the stuck tab row): the gallery's windowing keeps a tile the
// keyboard moves to below it (`MediaGrid`'s `--media-grid-occluded-top`). Measured, because the row's height is the
// theme's and its offset the shell's breakpoint's.
const detailEl = ref<HTMLElement | null>(null)
const occludedTop = ref(0)
let stickyObserver: ResizeObserver | null = null
function measureSticky() {
  const row = detailEl.value?.querySelector<HTMLElement>('.memory-tabs .veyq-tabs__bar')
  if (!row) return
  const top = parseFloat(getComputedStyle(row).top)
  occludedTop.value = Math.round((Number.isFinite(top) ? top : 0) + row.offsetHeight)
}
onMounted(() => {
  measureSticky()
  window.addEventListener('resize', measureSticky)
  const row = detailEl.value?.querySelector<HTMLElement>('.memory-tabs .veyq-tabs__bar')
  if (row && typeof ResizeObserver !== 'undefined') {
    stickyObserver = new ResizeObserver(measureSticky)
    stickyObserver.observe(row)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', measureSticky)
  stickyObserver?.disconnect()
})
provideMemoryView({ source: props.source, rights: can })
/** Who was there: the participants' model and picker, whose fact the header shows and opens the picker from. */
const participantsRow = ref<InstanceType<typeof MemoryParticipants> | null>(null)

/** The header's actions (core's header; their priority is the kind's `header.order`): each is shown only where `can` grants
 *  it; a share guest gets the same list with fewer entries. One share action opens the one dialog of every link role, shown
 *  where any role may be minted (`sharesAny`); the offer says which (`shareOfferOf`). */
const headerActions = computed<NodeHeaderAction[]>(() => [
  { id: 'upload', label: t('media.upload'), icon: 'cloud-upload', severity: 'primary', shown: can.value.upload, disabled: !props.memoryKey, onClick: () => triggerUpload() },
  { id: 'share', label: t('share.share'), icon: 'share-alt', shown: sharesAny(can.value), disabled: !props.memoryKey, onClick: () => emit('share', shareOfferOf(can.value)) },
  // The banner above the Memory (uc.memory.banner): who may choose the cover may set it (`setBanner`, a member's only). A drawn
  // banner opens its own dialog (`MemoryBanner`'s edit), so the menu offers it only while none is drawn: none set yet, or
  // one whose picture does not open (it draws nothing, E5 - the dialog still changes or removes it).
  {
    id: 'banner', label: memoryBanner.banner.value ? t('memory.banner.change') : t('memory.banner.add'), icon: 'objects-column', menuOnly: true,
    shown: can.value.setBanner && !bannerDrawn.value, disabled: !props.memoryKey, onClick: () => openBannerDialog(),
  },
  // Move and delete are core's standard actions (`standardNodeActions`, both in the ⋯ menu), granted by `can`; the move is
  // core's dialog (inbox #358): a Memory to the root or a folder, a chapter into another Memory. The rename is the name's own,
  // in place (`headerRename`).
  ...standardNodeActions(t, { move: can.value.move, delete: can.value.delete }, { move: () => emit('move'), remove: () => emit('delete') }),
])
/** The Memory's name, renamed in place in the header (the page's door), where `can` grants the rename. */
const headerRename = computed(() => standardNodeRename({ rename: can.value.rename }, props.renameTo ? { renameTo: props.renameTo } : {}))

// --- item favorites (ADR 0068) ---
// The marks inside this Memory, shared with the scope. The filter is declared BEFORE the gallery core because
// the core takes it as a predicate; `all` means no filter at all, so the unfiltered gallery pays nothing.
const markFilter = ref<'all' | 'everyone' | 'mine'>('all')
const markFilterPredicate = computed<((mediaId: string) => boolean) | null>(() => {
  if (markFilter.value === 'mine') return (id) => isMarkedByMe(id)
  if (markFilter.value === 'everyone') {
    const marked = markedByAnyone()
    return (id) => marked.has(id)
  }
  return null
})

/** Load every member's marks for this Memory — re-runs when the Memory or the scope key changes. */
async function loadMarks() {
  const key = props.scopeKey
  const marks = props.source.marks
  if (!key || !can.value.mark || !marks) return // marks are the scope members' (ADR 0068): no other session reads them
  try {
    await marks.load(props.node.id, key)
  } catch {
    /* marks are an accent on the gallery, never a precondition for it — an empty aggregate is the fallback */
  }
}

async function onToggleMark(mediaId: string) {
  const key = props.scopeKey
  const marks = props.source.marks
  if (!key || !can.value.mark || !marks) return
  try {
    await marks.toggle(mediaId, props.node.id, key)
  } catch (e) {
    // the heart is already back where it was; this says why (inbox #500)
    toast.error(t(e instanceof ItemMarksTooMany ? 'media.marks.tooMany' : 'media.marks.saveFailed'))
  }
}

watch(
  [() => props.node.id, () => props.scopeKey, () => can.value.mark] as const, // one by one (see `useMediaGalleryLive`'s)
  () => {
    if (can.value.mark) clearItemFavorites() // the store is the scope members'; a guest's view never touches it
    markFilter.value = 'all' // a filter is about the Memory you are in, not a mode you carry into the next
    void loadMarks()
  },
  { immediate: true },
)

// The live-gallery core (uc.memory.gallery) — listed media, the thumbnail pipeline (cr-0150) + optimistic tiles,
// the cr-0107 live poll (load/merge/upsert), the justified layout, and the optimistic-delete/undo — extracted into
// `useMediaGalleryLive` (cr-0151). `onSettle` runs the auto-date then auto-location reconcile after a list refresh.
const {
  items,
  thumbs,
  thumbFailed,
  layoutItems,
  hasContent,
  status,
  sortDir,
  lightboxItems,
  lightboxIndex,
  openLightboxAt,
  openLightboxOn,
  openBucketLightbox,
  onDeleteFromLightbox,
  onCapturedAtUpdated,
  load,
  pollMedia,
  liftTombstone,
  removeMedia,
  wantThumbs,
} = useMediaGalleryLive({
  source: props.source,
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  onSettle: () => settle(),
  markFilter: () => markFilterPredicate.value,
})
// The Trash tab (cr-0110/0111) lives in `useMediaTrash` (cr-0147): its listing, thumbnails, batch select, the
// restore/purge menu + confirm. A restore/purge lifts the gallery tombstone; a restore re-lists the gallery.
const {
  trashedItems,
  trashThumbs,
  trashThumbFailed,
  wantTrashThumbs,
  trashStatus,
  selectedTrash,
  trashMenu,
  purgeConfirm,
  purgeBusy,
  trashLayoutItems,
  trashMenuModel,
  loadTrash,
  resetTrash,
  toggleTrashSelect,
  allTrashSelected,
  toggleSelectAllTrash,
  selectedTrashLabel,
  openTrashMenu,
  restoreSelected,
  purgeSelected,
  confirmPurge,
} = useMediaTrash({
  source: props.source,
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  reloadGallery: () => void load(),
  liftTombstone,
})
// The persisted video signal (#104/#105): a media is a video iff its original's sealed type (its blob meta, inbox
// #397, served on the listing) is `video/*`. Read from the opened listing (`openedListing.ts`: opened once per seal, in
// the listing opener Worker), so the tile draws its play badge with no extra fetch and no open on a re-render. A media
// whose original carries no meta gets no badge.
function videoIdSet(list: readonly MediaItem[]): Set<string> {
  const key = props.memoryKey
  const set = new Set<string>()
  if (!key) return set
  for (const it of list) if (isVideoMediaType(mediaTypeOf(openedBlobMeta(props.scopeId, key, it)))) set.add(it.mediaId)
  return set
}
const galleryVideoIds = computed(() => videoIdSet(items.value))
const trashVideoIds = computed(() => videoIdSet(trashedItems.value))

const activeTab = ref<'gallery' | 'chapters' | 'beitraege' | 'trash'>('gallery') // local (the #fragment carries the tree path, cr-0031)
const fileInput = ref<HTMLInputElement | null>(null)

// --- Memory date (cr-0067, uc.memory.date) → extracted into `useMemoryDate` (cr-0145). The effective range
// is sealed on the NODE under K_node (operator-blind) and shown in the header; `auto` materializes from the
// Memory's own media and self-heals on open + after an upload batch (via `reconcileAuto`, called at the settle
// points below). Props are passed as getters so the composable stays reactive to them.
const { dateEditorOpen, storedDate, autoRange, dateLabel, datedChapters, onDateSave, reconcileAuto } = useMemoryDate({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  chapters: () => props.chapters,
  write: () => (can.value.editMeta ? (props.source.nodeMeta ?? null) : null),
  items,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})

// --- Who added the shown photo (ADR 0083 decision 7): read from the buckets door when the lightbox opens. A guest's
// source has no such door, so a guest's lightbox shows no uploader - as it never did.
const uploaderNames = useUploaderNames({
  scopeId: () => props.scopeId,
  nodeId: () => props.node.id,
  memoryKey: () => props.memoryKey,
  buckets: () => (can.value.buckets ? (props.source.buckets ?? null) : null),
  formerLabel: () => t('memory.participants.former'),
  contributorLabel: () => t('memory.buckets.contributor'),
})
watch(lightboxIndex, (i) => {
  if (i !== null) void uploaderNames.ensure()
})

// --- Memory location (cr-0069, uc.memory.place) → extracted into `useMemoryLocation` (cr-0146). A deliberate
// per-Memory named place sealed in the SAME node `wrappedMeta` blob as the date (read-modify-write); the map +
// geocoding gate behind a personal two-stage opt-in (ADR 0016/0018). `reconcileAutoLocation` is called at the
// settle points below. Props are passed as getters so the composable stays reactive to them.
const {
  locationEditorOpen,
  consentDialogOpen,
  storedLocation,
  gpsPoints,
  medoid,
  locationLabel,
  locationResolved,
  openLocationEditor,
  enableMaps,
  onLocationSave,
  reconcileAutoLocation,
} = useMemoryLocation({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  write: () => (can.value.editMeta ? (props.source.nodeMeta ?? null) : null),
  items,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})

// --- what core's header shows under the name (ADR 0110 decision 10): the date and the place as chips that open this view's
// editors (greyed where the meta may not be written: a guest, a pending deletion, keys not loaded), then who was there. The
// cover is core's own (the kind's `cover`; core's page resolves and shows it, and opens core's dialog from it). A place
// that is unset and may not be set is left out: its only words are the invitation "Set place", which reads as an offer to
// whoever cannot take it (owner 2026-11-07); an unset date keeps its chip, "No date yet" being a fact.
const headerFacts = computed<NodeHeaderFact[]>(() => {
  const locked = !props.memoryKey || !can.value.editMeta
  const people = participantsRow.value?.fact
  const place: NodeHeaderFact[] = locked && !locationResolved.value ? [] : [{
    kind: 'place', label: locationLabel.value || t('memory.place.set'), empty: !locationResolved.value, disabled: locked,
    actionLabel: locationResolved.value ? t('memory.place.edit') : t('memory.place.set'), onClick: () => openLocationEditor(),
  }]
  return [
    {
      kind: 'date', label: dateLabel.value || t('memory.date.noDate'), empty: !dateLabel.value, disabled: locked,
      actionLabel: t('memory.date.edit'), onClick: () => (dateEditorOpen.value = true),
    },
    ...place,
    ...(people ? [people] : []),
  ]
})

const bannerEditor = useBannerEditor({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  source: props.source,
  items: () => items.value,
  onMetaUpdated: (update) => emit('metaUpdated', update),
})

// Core's header of this page: the count, the actions, the facts, and the banner above it - the same for a member and a guest
// (the banner offers no action of its own).
contributeNodeHeader(() => ({
  subtitle: t('media.itemCount', { count: items.value.length }, items.value.length),
  actions: headerActions.value,
  rename: headerRename.value,
  facts: headerFacts.value,
  banner: {
    component: MemoryBanner,
    props: {
      banner: memoryBanner.banner.value,
      pictureUrl: memoryBanner.pictureUrl.value,
      status: memoryBanner.status.value,
      videoChunks: memoryBanner.videoChunks,
      videoBytes: memoryBanner.videoBytes,
      // The banner opens its own dialog where it may be changed (a member's `setBanner`); a guest's offers nothing.
      edit: can.value.setBanner && props.memoryKey ? openBannerDialog : null,
    },
  },
}))

// Core's search (ADR 0125 decision 6): the photos and videos of the gallery's opened listing, as documents of this node, for
// as long as this page is on screen - a no-op on a guest's page, which provides no search. Opening one opens the lightbox
// here; the documents are built only when the search reads them.
contributeSearchDocuments(() =>
  props.memoryKey
    ? {
        scopeId: props.scopeId,
        nodeId: props.node.id,
        documents: mediaDocuments({
          scopeId: props.scopeId, key: props.memoryKey, items: items.value,
          unnamed: (video) => t(video ? 'memory.search.video' : 'memory.search.photo'),
          open: (mediaId) => {
            activeTab.value = 'gallery'
            openLightboxOn(mediaId)
          },
        }),
      }
    : null,
)

// A photo the search found from elsewhere (ADR 0125 decision 8a): the search navigated here and asked for it in core's
// open request; it opens in the lightbox once the gallery holds it, and a request the read gallery cannot answer (the
// medium gone meanwhile) is dropped. A guest's page never receives one (a guest has no search).
watch(
  [searchOpenRequest, items, status] as const,
  () => {
    const want = takeOpenRequest(props.scopeId, props.node.id, (id) => items.value.some((it) => it.mediaId === id), status.value !== 'loading')
    if (!want) return
    activeTab.value = 'gallery'
    openLightboxOn(want)
  },
  { immediate: true },
)

/** The reconcile settle point `useMediaGalleryLive` calls after each list refresh: auto-date first, then
 *  auto-location (which reads the date-reconciled wrappedMeta patched in place → read-modify-write safe). Both WRITE the
 *  node's sealed meta, so they run only where the meta may be edited (`rights.editMeta`).
 *  One settle at a time: an upload's last file both polls (`onFileDone`) and re-lists (`onComplete`), and each refresh
 *  settles. Run side by side, both built their write on the same `metaRevision`, so the second was the server's
 *  `409 stale_revision` (a console error) for a range the first had just written. A settle asked for while one runs runs
 *  once after it, on the meta the first one patched in and on the list as it is then. */
let settling: Promise<void> | null = null
let settleAgain = false
function settle(): Promise<void> {
  if (settling) {
    settleAgain = true
    return settling
  }
  settling = (async () => {
    try {
      do {
        settleAgain = false
        if (!can.value.editMeta) return
        await reconcileAuto()
        await reconcileAutoLocation()
      } while (settleAgain)
    } finally {
      settling = null
    }
  })()
  return settling
}

// The Beiträge tab (the media organizer, MemoryBuckets.vue) — where a member creates sub-buckets and files their own
// uploads (cr-0113); a member's only (`rights.buckets`: it reads the scope's members and the bucket API). Its own
// state/loading live in that component; here we only host it + wire the lightbox/upload/reload it emits.
const showBeitraege = computed(() => can.value.buckets)

/** The organizer changed buckets/media (create/rename/delete/move) — re-list so each item's ownerKey/bucketId refresh. */
function onBucketChanged() { void load() }
/** Upload from the organizer — reuses the Memory upload; the caller's primary materializes on complete (moving into a
 *  specific sub-bucket after upload is a follow-up; for now uploads land in the primary, then move them in). */
function onBucketUpload() { triggerUpload() }

// --- upload trigger (uc.memory.upload). The Safari Photos picker populates input.files LATE / across repeated
// picks with empty MIME types, so a pick runs through `collectPickedFiles` — the shared E3 window that re-reads
// the input, merges by signature and releases it afterwards. It lives in the queue module because the
// contributor's picker in ShareView needs the identical behaviour; the window's rules (and why the deferred
// clear is load-bearing) are documented there, once.
let cancelPickWindow: (() => void) | null = null

function triggerUpload() {
  const upload = props.source.upload
  if (upload && !upload.ready()) {
    // A guest names itself first, through the page's one flow; the next click opens the picker (one opened after an
    // awaited dialog would no longer count as the visitor's own click).
    void upload.prepare()
    return
  }
  fileInput.value?.click()
}

function onFilesPicked() {
  const input = fileInput.value
  if (!input) return
  cancelPickWindow?.() // a fresh pick supersedes whatever the previous window was still waiting for
  cancelPickWindow = collectPickedFiles(input, enqueueBatch)
}

onBeforeUnmount(() => cancelPickWindow?.())

function enqueueBatch(picked: File[]) {
  const key = props.memoryKey
  const upload = props.source.upload
  if (!key || !can.value.upload || !upload) return
  const targetNodeId = props.node.id
  // Into the node on screen, sealed under its key: a member's `addMedia`; a guest's upload names this node (the server
  // creates the guest's own bucket there).
  const target = upload.target(targetNodeId, key, props.node.epoch)
  if (!target) return
  enqueueUploads(
    target,
    picked.map((file) => ({ file, mediaType: inferMediaType(file) })),
    {
      label: props.node.name,
      // cr-0111: reconcile each file's optimistic tile the moment IT finishes — a targeted live poll lists just
      // the newly-attached media so its tile flips to the real thumbnail without waiting for the whole batch.
      onFileDone: () => { if (props.node.id === targetNodeId) void pollMedia() },
      onComplete: () => { if (props.node.id === targetNodeId) void load() }, // final settle (date/location self-heal)
    },
  )
}

// --- a guest's delete of its own media (inbox #361): irreversible — the server purges it and keeps no copy — so asked
// first, then gone from the grid at once. A member's delete is the lightbox's trash with undo (`useMediaGalleryLive`).
const deletingOwn = ref<Set<string>>(new Set())
/** A tile carries the guest's delete only on what this device sent under the link's identity. */
function isOwnMedia(mediaId: string): boolean {
  const del = props.source.deleteMedia
  return can.value.deleteMedia === 'own' && del?.reach === 'own' && del.isOwn(mediaId)
}
async function onDeleteOwn(mediaId: string) {
  const del = props.source.deleteMedia
  if (can.value.deleteMedia !== 'own' || del?.reach !== 'own' || deletingOwn.value.has(mediaId)) return
  const confirmed = await ask({
    header: t('share.deletePhoto'),
    message: t('share.deleteOwnConfirm'),
    acceptLabel: t('share.deleteOwnAccept'),
    acceptIcon: 'trash',
  })
  if (!confirmed) return
  deletingOwn.value = new Set(deletingOwn.value).add(mediaId)
  try {
    await del.purge(mediaId)
    removeMedia(mediaId)
  } catch {
    toast.error(t('share.deleteFailed')) // the tile stays, so the guest can try again
  } finally {
    const next = new Set(deletingOwn.value)
    next.delete(mediaId)
    deletingOwn.value = next
  }
}

/** The lightbox's delete on the shown item: a member's trash on any, a guest's purge on its own only. */
function canDeleteInLightbox(it: MediaItem): boolean {
  return can.value.deleteMedia === 'any' || isOwnMedia(it.mediaId)
}
/** The lightbox asked to delete: the door decides how — a trash with undo, or a guest's purge asked first. */
function onLightboxDelete(payload: { mediaId: string }) {
  if (can.value.deleteMedia === 'own') void onDeleteOwn(payload.mediaId)
  else if (can.value.setBanner && isBannerPicture(payload.mediaId)) void onDeleteBannerPicture(payload)
  else void onDeleteFromLightbox(payload)
}

/**
 * Deleting the medium that is the banner's picture (ADR 0102 decision 7): the confirmation says the banner stays - its
 * stored bytes go only with the last row naming them - and offers to remove it in the same step. Nothing else ties the two:
 * declined, nothing is deleted; accepted, the medium goes to the trash as any other (with its undo), and the banner goes
 * too only where that was ticked.
 */
async function onDeleteBannerPicture(payload: { mediaId: string }) {
  const answer = await askWithOption({
    header: t('memory.banner.pictureTitle'),
    message: t('memory.banner.pictureNotice'),
    acceptLabel: t('media.delete'),
    acceptIcon: 'trash',
    option: { label: t('memory.banner.pictureRemove') },
  })
  if (!answer) return
  void onDeleteFromLightbox(payload)
  if (!answer.option) return
  try {
    await bannerEditor.save({ kind: 'remove' })
  } catch {
    toast.error(t('memory.banner.failed'))
  }
}

// The lightbox's bytes and seals through the source, under this node's key (the lightbox mounts only with one).
const lightboxKey = computed(() => props.memoryKey ?? new Uint8Array())
const loaders = props.source.loaders
const previewLoader = (it: MediaItem) => loaders.preview(it, lightboxKey.value)
const originalLoader = (it: MediaItem) => loaders.original(it, lightboxKey.value)
const chunkLoader = (it: MediaItem) => loaders.chunk(it, lightboxKey.value)
const metaResolver = (it: MediaItem) => loaders.meta(it, lightboxKey.value)
const blobMetaResolver = (it: MediaItem) => loaders.blobMeta(it, lightboxKey.value)
const exifLoader = loaders.exif ? (it: MediaItem) => loaders.exif!(it, lightboxKey.value) : undefined

// The grids a lightbox opens from: closing it scrolls the shown item's tile into view and focuses it (ADR 0105
// decision 5) — in the gallery, or in the contributors' view when it was opened there.
type FocusableGrid = { focusItem: (id: string) => Promise<void> | void }
const galleryGrid = ref<FocusableGrid | null>(null)
const bucketsView = ref<FocusableGrid | null>(null)
async function onLightboxClose(mediaId: string | null) {
  lightboxIndex.value = null
  if (!mediaId) return
  await nextTick()
  const grid = activeTab.value === 'beitraege' ? bucketsView.value : activeTab.value === 'gallery' ? galleryGrid.value : null
  await grid?.focusItem(mediaId)
}

// The Trash tab loads lazily on open — no extra list on every Memory open.
watch(activeTab, (tab) => { if (tab === 'trash') void loadTrash() })

// On a Memory/chapter switch: reset the tab to the gallery + drop the previous Memory's trash state. (The gallery
// grid + live-poll lifecycle is owned by `useMediaGalleryLive`, which watches the same node/key + tears down itself.)
watch(
  [() => props.node.id, () => props.memoryKey] as const, // one by one (see `useMediaGalleryLive`'s)
  () => {
    activeTab.value = 'gallery'
    resetTrash() // cr-0110/0111: drop the previous Memory's trash listing/thumbs/selection
  },
  { immediate: true },
)
</script>

<template>
  <div ref="detailEl" class="memory-route-surface memory-detail" :style="{ '--media-grid-occluded-top': `${occludedTop}px` }">
    <!-- The picker the header's upload action opens (`triggerUpload`): a plain file input, out of sight. -->
    <input
      v-if="can.upload"
      ref="fileInput"
      type="file"
      multiple
      accept="image/*,video/*,.heic,.heif,.mov,.m4v,.mkv,.avi,.tif,.tiff"
      class="visually-hidden"
      @change="onFilesPicked"
    />
    <!-- Who was there (ADR 0083): a member's alone - `can.participants` is false for every share role, so a guest's view
         mounts the component with `show` false and it offers no fact. A personal scope has nobody to name. It draws only its
         picker, which opens from its fact in core's header (the people's edit button, or the empty chip). -->
    <MemoryParticipants
      ref="participantsRow"
      :scope-id="scopeId"
      :node="node"
      :memory-key="memoryKey"
      :show="can.participants && !personalScope"
      :write="can.editMeta && !personalScope ? (source.nodeMeta ?? null) : null"
      @meta-updated="emit('metaUpdated', $event)"
    />

    <!-- Tabbed detail (cr-0061, ported from the design source): Gallery (the media grid) + Chapters (the
         nested Memories). Other design tabs — journal / map / playlists — arrive with their features. -->
    <VeyqTabs v-model:value="activeTab" class="memory-tabs">
      <VeyqTabList>
        <VeyqTab value="gallery">{{ t('memory.gallery') }}</VeyqTab>
        <VeyqTab v-if="can.navigate" value="chapters">
          {{ t('memory.chapters') }}<span v-if="chapters && chapters.length" class="tab-count">{{ chapters.length }}</span>
        </VeyqTab>
        <VeyqTab v-if="showBeitraege" value="beitraege">{{ t('memory.buckets.title') }}</VeyqTab>
        <VeyqTab v-if="can.mediaTrash" value="trash">{{ t('memory.trash.title') }}</VeyqTab>
      </VeyqTabList>
      <VeyqTabPanels>
        <VeyqTabPanel value="gallery">
          <div v-if="status === 'loading' && !hasContent" class="media-grid-placeholder">
            <VeyqIcon name="spinner" spin />
            <span>{{ t('media.loading') }}</span>
          </div>
          <p v-else-if="status === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>
          <p v-else-if="!hasContent" class="veyq-empty">{{ t('media.empty') }}</p>
          <template v-else>
            <div v-if="items.length" class="gallery-toolbar">
              <button
                class="gallery-sort-toggle"
                type="button"
                :aria-label="sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest')"
                :title="sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest')"
                @click="sortDir = sortDir === 'newest' ? 'oldest' : 'newest'"
              >
                <VeyqIcon :name="sortDir === 'newest' ? 'sort-amount-down' : 'sort-amount-up-alt'" />
                <span>{{ sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest') }}</span>
              </button>
              <!-- The mark filter. In a personal scope there is nobody to share a mark with, so it is HIDDEN
                   rather than disabled (uc.memory.gallery E4) — a greyed-out control invites the question
                   "how do I enable this?", which has no answer. Its presence is also half of what tells the
                   user that marks here are seen by the others (ADR 0068). -->
              <div v-if="can.mark && !personalScope" class="gallery-mark-filter" role="group" :aria-label="t('media.marks.filterLabel')">
                <button
                  v-for="opt in (['all', 'everyone', 'mine'] as const)"
                  :key="opt"
                  type="button"
                  class="gallery-mark-filter-option"
                  :class="{ 'is-active': markFilter === opt }"
                  :aria-pressed="markFilter === opt"
                  @click="markFilter = opt"
                >
                  {{ t(`media.marks.filter.${opt}`) }}
                </button>
              </div>
            </div>
            <!-- E5: a filter that empties the grid names itself, so it never reads as a broken Memory. -->
            <p v-if="markFilter !== 'all' && !layoutItems.length" class="veyq-empty">
              {{ markFilter === 'mine' ? t('media.marks.emptyMine') : t('media.marks.emptyEveryone') }}
            </p>
            <MediaGrid
              v-else
              ref="galleryGrid"
              :items="layoutItems"
              :aria-label="t('media.gallery')"
              @window="(ids) => wantThumbs('gallery', ids)"
            >
              <template #tile="{ tile, item }">
                <!-- optimistic in-grid upload tile (cr-0074): the shared OptimisticTile (preview + progress / retry) -->
                <OptimisticTile
                  v-if="item.kind === 'optimistic'"
                  :tile="item.optimistic"
                  :link="source.upload?.viaLink === true"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  @retry="retryFile"
                />
                <MediaTile
                  v-else
                  :data-media-id="item.media.mediaId"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  :src="thumbs.get(item.media.mediaId)"
                  :failed="thumbFailed.has(item.media.mediaId)"
                  :blocked="item.media.blocked"
                  :has-derivatives="hasDerivatives(item.media)"
                  :is-video="galleryVideoIds.has(item.media.mediaId)"
                  :open-label="t('media.openItem', { index: item.index + 1 })"
                  :markable="can.mark && !!scopeKey"
                  :mark-count="!can.mark || personalScope ? 0 : markCount(item.media.mediaId)"
                  :marked-by-me="can.mark && isMarkedByMe(item.media.mediaId)"
                  :mark-label="can.mark && isMarkedByMe(item.media.mediaId) ? t('media.marks.unmark') : t('media.marks.mark')"
                  @open="openLightboxAt(item.index)"
                  @toggle-mark="onToggleMark(item.media.mediaId)"
                >
                  <!-- a guest's own photo (#361): its delete beside the open button, asked first -->
                  <template v-if="isOwnMedia(item.media.mediaId)" #overlay>
                    <button
                      type="button"
                      class="share-own-delete"
                      :disabled="deletingOwn.has(item.media.mediaId)"
                      :aria-label="t('share.deletePhoto')"
                      :title="t('share.deletePhoto')"
                      @click.stop="onDeleteOwn(item.media.mediaId)"
                    >
                      <VeyqIcon :name="deletingOwn.has(item.media.mediaId) ? 'spinner' : 'trash'" :spin="deletingOwn.has(item.media.mediaId)" />
                    </button>
                  </template>
                </MediaTile>
              </template>
            </MediaGrid>
          </template>
        </VeyqTabPanel>

        <VeyqTabPanel v-if="can.navigate" value="chapters">
          <!-- Chapters as a MemoryCard grid + a create card, like the level's Memories (design source).
               Each chapter opens to its own gallery; its media is sealed under the chapter's own K_node. -->
          <div class="chapters-toolbar">
            <ViewModeToggle :views="[GRID_VIEW, LIST_VIEW]" />
          </div>
          <div
            class="memory-card-grid"
            :class="{ 'is-list': viewMode === 'list' }"
            :aria-label="t('memory.chapters')"
          >
            <MemoryCard
              v-for="{ node: chapter, date } in datedChapters"
              :key="chapter.id"
              :memory="chapter"
              :date="date"
              @open="$emit('openChapter', $event)"
            />
            <button
              v-if="can.createChapter"
              class="veyq-card veyq-card-interactive veyq-create-card memory-create-card-memory"
              type="button"
              :disabled="!memoryKey"
              @click="$emit('createChapter')"
            >
              <VeyqIcon name="plus" />
              <strong>{{ t('memory.addChapter') }}</strong>
            </button>
          </div>
        </VeyqTabPanel>

        <!-- Beiträge tab (uc.memory.buckets, cr-0113): the media ORGANIZER — a hierarchy picker (your primary +
             sub-buckets), create/rename/delete sub-buckets, move media in, upload into a bucket. Ported from the
             legacy MemoryAssetBuckets UX. Always present; the primary is virtual until a sub-bucket/upload materializes it. -->
        <VeyqTabPanel v-if="showBeitraege" value="beitraege">
          <MemoryBuckets
            ref="bucketsView"
            :scope-id="scopeId"
            :node-id="node.id"
            :node-epoch="node.epoch"
            :memory-key="memoryKey"
            :media="items"
            :thumbs="thumbs"
            :failed="thumbFailed"
            @open-lightbox="openBucketLightbox"
            @changed="onBucketChanged"
            @upload-bucket="onBucketUpload"
            @window="(ids) => wantThumbs('buckets', ids)"
          />
        </VeyqTabPanel>

        <!-- Trash tab (uc.memory.delete-media, cr-0110/0111): the Memory's soft-deleted media in the SAME justified
             MediaGrid, with a per-tile checkbox (batch select) + a 3-dot menu (restore / purge). Lazy on open. -->
        <VeyqTabPanel v-if="can.mediaTrash" value="trash">
          <div v-if="trashStatus === 'loading'" class="media-grid-placeholder">
            <VeyqIcon name="spinner" spin />
            <span>{{ t('media.loading') }}</span>
          </div>
          <p v-else-if="trashStatus === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>
          <p v-else-if="trashedItems.length === 0" class="veyq-empty">{{ t('memory.trash.empty') }}</p>
          <template v-else>
            <div class="memory-trash-toolbar">
              <span class="memory-trash-hint">{{ t('memory.trash.hint') }}</span>
              <!-- Selection bar — always visible; actions enable with a selection (cr-0152, the Beiträge mechanic). -->
              <div class="memory-trash-batch">
                <span class="memory-trash-batch-count">{{ selectedTrashLabel }}</span>
                <button type="button" class="veyq-text-button" @click="toggleSelectAllTrash">
                  {{ allTrashSelected ? t('memory.trash.deselectAll') : t('memory.trash.selectAllVisible') }}
                </button>
                <button type="button" class="bucket-action-button" :disabled="!selectedTrash.size" @click="restoreSelected">
                  <VeyqIcon name="replay" /><span class="bucket-action-label">{{ t('memory.trash.restore') }}</span>
                </button>
                <button type="button" class="bucket-action-button bucket-action-danger" :disabled="!selectedTrash.size" @click="purgeSelected">
                  <VeyqIcon name="trash" /><span class="bucket-action-label">{{ t('memory.trash.purge') }}</span>
                </button>
              </div>
            </div>
            <MediaGrid :items="trashLayoutItems" :aria-label="t('memory.trash.title')" @window="wantTrashThumbs">
              <template #tile="{ tile, item }">
                <MediaTile
                  class="memory-trash-tile"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  :src="trashThumbs.get(item.media.mediaId)"
                  :failed="trashThumbFailed.has(item.media.mediaId)"
                  :blocked="item.media.blocked"
                  :has-derivatives="hasDerivatives(item.media)"
                  :is-video="trashVideoIds.has(item.media.mediaId)"
                  :open-label="t('media.openItem', { index: item.index + 1 })"
                  :selected="selectedTrash.has(item.media.mediaId)"
                  @open="toggleTrashSelect(item.media.mediaId)"
                >
                  <template #overlay>
                    <label class="media-tile-check" :class="{ 'is-on': selectedTrash.has(item.media.mediaId) }">
                      <input type="checkbox" :checked="selectedTrash.has(item.media.mediaId)" :aria-label="t('media.openItem', { index: item.index + 1 })" @click.stop @change="toggleTrashSelect(item.media.mediaId)" />
                      <VeyqIcon name="check" />
                    </label>
                    <button
                      type="button"
                      class="media-tile-menu"
                      :aria-label="t('common.more')"
                      @click.stop="openTrashMenu($event, item.media.mediaId)"
                    >
                      <VeyqIcon name="ellipsis-v" />
                    </button>
                  </template>
                </MediaTile>
              </template>
            </MediaGrid>
            <VeyqMenu ref="trashMenu" :items="trashMenuModel" />
          </template>
        </VeyqTabPanel>
      </VeyqTabPanels>
    </VeyqTabs>

    <MediaLightbox
      v-if="lightboxIndex !== null && memoryKey"
      :memory-key="memoryKey"
      :memory-node="node"
      :items="lightboxItems"
      :start-index="lightboxIndex"
      :memory-name="node.name"
      :suspend-keys="coverEditing || bannerDialog"
      :can-delete="canDeleteInLightbox"
      :media-meta="source.mediaMeta"
      :uploader-of="uploaderNames.uploaderOf"
      :preview-loader="previewLoader"
      :original-loader="originalLoader"
      :video-chunk-provider="chunkLoader"
      :meta-resolver="metaResolver"
      :blob-meta-resolver="blobMetaResolver"
      :exif-loader="exifLoader"
      :can-report="canReport"
      @close="onLightboxClose"
      @updated="onCapturedAtUpdated"
      @delete="onLightboxDelete"
      @report="emit('reportMedium', $event)"
    />

    <BannerDialog
      v-if="bannerDialog && memoryKey && can.setBanner"
      :current="memoryBanner.banner.value"
      :current-src="memoryBanner.pictureUrl.value"
      :save="bannerEditor.save"
      @close="bannerDialog = false"
    />

    <MemoryDateEditor
      v-if="dateEditorOpen && memoryKey"
      :date="storedDate"
      :auto-range="autoRange"
      @save="onDateSave"
      @close="dateEditorOpen = false"
    />

    <MemoryLocationEditor
      v-if="locationEditorOpen && memoryKey"
      :location="storedLocation"
      :medoid="medoid"
      :points="gpsPoints"
      @save="onLocationSave"
      @close="locationEditorOpen = false"
    />

    <VeyqDialog
      :visible="consentDialogOpen"
      :header="t('memory.place.consentTitle')"
      class="memory-location-consent"
      :style="{ width: 'min(28rem, 92vw)' }"
      @update:visible="consentDialogOpen = false"
    >
      <p>{{ t('memory.place.consentBody') }}</p>
      <MapConsentRenewal />
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" @click="consentDialogOpen = false" />
        <VeyqButton :label="t('memory.place.consentEnable')" icon="map" @click="enableMaps" />
      </div>
    </VeyqDialog>

    <!-- Confirm the irreversible purge (cr-0111): permanently deletes the trashed media NOW (bypasses the 30-day retention). -->
    <VeyqDialog
      :visible="purgeConfirm !== null"
      :header="t('memory.trash.purgeConfirmTitle')"
      class="memory-trash-purge-dialog"
      :closable="!purgeBusy"
      :close-on-escape="!purgeBusy"
      @update:visible="purgeConfirm = null"
    >
      <p>{{ t('memory.trash.purgeConfirmBody', { count: purgeConfirm?.ids.length ?? 0 }, purgeConfirm?.ids.length ?? 0) }}</p>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="purgeBusy" @click="purgeConfirm = null" />
        <VeyqButton :label="t('memory.trash.purge')" icon="trash" variant="danger" :loading="purgeBusy" @click="confirmPurge" />
      </div>
    </VeyqDialog>
  </div>
</template>

<style scoped>
.gallery-mark-filter {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
}
.gallery-mark-filter-option {
  padding: 4px 10px;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-muted);
  font-size: 0.8125rem;
  cursor: pointer;
}
.gallery-mark-filter-option.is-active {
  background: var(--app-surface);
  color: var(--app-text);
}

.gallery-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.6rem;
}
/* Icon + label pill (NOT the icon-only .veyq-icon-button utility, which forces a square grid-centered box and
   stacks the icon over the label). Matches the header date/place chips for a consistent toolbar affordance. */
.gallery-sort-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.35rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: var(--app-surface);
  color: var(--app-text);
  font-size: 0.86rem;
  cursor: pointer;
}
.gallery-sort-toggle:hover {
  background: var(--app-hover);
}
.gallery-sort-toggle:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.gallery-sort-toggle .veyq-icon {
  font-size: 0.9rem;
}
/* The tab row sticks below the shell's sticky chrome (`--app-sticky-top`: the top bar, the compact header on a phone,
   0 on a share page) while the banner and the header row scroll away (uc.memory.banner, owner's Q3). The page scrolls
   the WINDOW, and so does the gallery's windowing (`MediaGrid` takes the nearest scrolling ancestor, which is the window
   here): one scroller, so the row sticks over exactly what the grid measures, and `MediaGrid` reads how far down the
   sticky stack reaches from `--media-grid-occluded-top` (set below from the row's measured bottom). */
.memory-tabs :deep(.veyq-tabs__bar) {
  position: sticky;
  top: var(--app-sticky-top, 0px);
  z-index: 6;
  background: var(--app-bg);
}
/* The Chapters tab's view toggle, right-aligned above the card grid. */
.chapters-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.75rem;
}
/* A small count next to the Chapters tab label. */
.tab-count {
  margin-left: 0.4rem;
  padding: 0 0.4rem;
  border-radius: 999px;
  background: var(--app-control-bg);
  color: var(--app-muted);
  font-size: 0.75rem;
  font-weight: 700;
}
/* Beiträge tab (cr-0113): the uploader picker — a row of chips (label + count), the active one accented. */
.beitraege-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.beitraege-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.3rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.beitraege-chip:hover {
  background: var(--app-control-bg);
}
.beitraege-chip.is-active {
  border-color: color-mix(in srgb, var(--app-accent) 55%, var(--app-line));
  background: color-mix(in srgb, var(--app-accent) 12%, transparent);
}
.beitraege-chip-count {
  padding: 0 0.35rem;
  border-radius: 999px;
  background: var(--app-control-bg);
  color: var(--app-muted);
  font-size: 0.75rem;
  font-weight: 700;
}
/* The justified-rows gallery (cr-0059): aspect-preserving tiles laid out by calculateJustifiedMediaLayout into
   rows that fill the container width (the per-tile aspect ratio comes from the E2EE-sealed dimensions). The
   container is position:relative with an explicit height; each tile is absolutely positioned via a transform +
   its rendered width/height. The tile-inner classes (.media-grid-tile-media / -placeholder / -icon) are the
   design source's (media.css). */
.gallery-grid {
  position: relative;
  width: 100%;
}
/* Gallery tiles are the shared MediaTile (cr-0125) — its wrapper/fit/icon styles live in the component + Memories'
   global memories.css. The optimistic upload tile's own styles live in the shared OptimisticTile component (cr-0074). */

/* --- Trash tab (cr-0110/0111): the same gallery grid, dimmed tiles + select-check + 3-dot menu --- */
.memory-trash-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}
.memory-trash-hint {
  color: var(--app-muted);
  font-size: 0.85rem;
}
/* The selection bar mirrors the Beiträge .bucket-selection-bar exactly (cr-0152): muted un-bolded count,
   the stock .veyq-text-button select-all toggle, and the shared .bucket-action-button actions (memories.css). */
.memory-trash-batch {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.memory-trash-batch-count {
  color: var(--app-muted);
  font-size: 0.85rem;
}
/* A trashed tile IS the shared MediaTile (cr-0125), just with the trashed media dimmed (the check/menu/outline are
   the standard `.media-tile*`). `:deep` reaches the image inside the child component. */
.memory-trash-tile :deep(.media-tile-img) {
  opacity: 0.72; /* trashed media reads as dimmed */
  transition: opacity 0.15s ease;
}
.memory-trash-tile.is-selected :deep(.media-tile-img) {
  opacity: 0.92;
}
/* A guest's delete on its own photo (#361): the tile's mark toggle's look (MediaTile), at the opposite corner — a
   surface scrim legible over any photo in either theme; the danger tone on hover. (`MemoryShareBody` carries the same
   rule until S5 of #401 deletes that body.) */
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
