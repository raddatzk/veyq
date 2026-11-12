<script setup lang="ts">
// A Memory's Map tab (uc.memory.tracks, ADR 0130 decision 4): its photos' GPS as dots and its GPX tracks as lines on one map,
// the tracks listed under it. A track is a medium (decision 1), handed in as `tracks` by the gallery's listing; this view
// reads each one's original through the session's loader only while it is on screen (`useMemoryTracks`), so a member and a
// share guest see the same tab, with the actions the detail's rights grant (`canAdd`, `canRemove`, `canDownload`,
// `canReport`) - nothing here checks a role.
//
// The map follows every map's three states (uc.memory.place A5): no map on this server, the consent prompt, the map with
// its *Turn map services off*. Without a map the list, the numbers, the download, the removal and the add stay (decision 6).
// Adding checks each picked file on the device (`checkGpxFile`: size, a GPX track, the node's 50) and hands the accepted ones
// to the detail, which sends them through the one upload queue (`add`); a refused file is named in a toast.
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import type { VeyqMenuItem } from '../../../shared/ui/menu'
import { useMapConfig } from '../../../shared/state/useMapConfig'
import { useToast } from '../../../shared/state/useToast'
import { SaveTooLarge, isSaveCancelled } from '../../../shared/saveStream'
import { effectiveCapturedAt, type MediaItem } from '../media'
import { WORLD_VIEW, type LatLng } from '../memoryLocation'
import type { MediaLoaders } from '../source'
import { downloadOriginal, originalDownloadName } from '../download'
import { checkGpxFile, isGpxFile, GPX_MEDIA_TYPE } from '../track/gpx'
import { trackFacts } from '../track/trackFacts'
import { useMemoryTracks, type TrackEntry } from '../track/useMemoryTracks'
import MemoryMap, { type MapTrack } from './MemoryMap.vue'
import MapConsentPrompt from './MapConsentPrompt.vue'

const props = defineProps<{
  scopeId: string
  nodeId: string
  memoryName: string
  memoryKey: Uint8Array | null
  /** The node's tracks (`useMediaGalleryLive`'s `tracks`). */
  tracks: readonly MediaItem[]
  /** The Memory's photos' GPS (`useMemoryLocation`'s `gpsPoints`). */
  photoPoints: readonly LatLng[]
  /** The tab is on screen: tracks are read only then. */
  active: boolean
  loaders: MediaLoaders
  canAdd: boolean
  /** Whether the track `mediaId` may be removed here (a member's any, a guest's own). */
  canRemove: (mediaId: string) => boolean
  canDownload: boolean
  canReport: boolean
  /** Asked before the picker opens: false when the session must get ready first (a guest names itself). */
  pickReady?: () => boolean
}>()
const emit = defineEmits<{
  /** Files checked as tracks, for the upload queue. */
  add: [files: File[]]
  remove: [item: MediaItem]
  report: [item: MediaItem]
}>()

const { t, locale } = useI18n()
const toast = useToast()
const { mapsOffered, mapsAvailable, tileUrl, tileAttribution, tileMaxZoom } = useMapConfig()

const tracksModel = useMemoryTracks({
  scopeId: () => props.scopeId,
  nodeId: () => props.nodeId,
  memoryKey: () => props.memoryKey,
  tracks: () => props.tracks,
  active: () => props.active,
  original: (item, key) => props.loaders.original(item, key),
  blobMeta: (item, key) => props.loaders.blobMeta(item, key),
  capturedAt: (item, key) => effectiveCapturedAt(props.loaders.meta(item, key)),
})
const { entries, hidden, selected, setShown, select } = tracksModel

/** A track's name as listed: its own, its file's, else its place in the list. */
function nameOf(entry: TrackEntry, index: number): string {
  return entry.name ?? t('memory.tracks.unnamed', { n: index + 1 })
}

const mapTracks = computed<MapTrack[]>(() =>
  entries.value.flatMap((entry, index) => {
    if (!entry.lines || hidden.value.has(entry.mediaId)) return []
    const name = nameOf(entry, index)
    return [{
      id: entry.mediaId, color: entry.color, lines: entry.lines, label: name,
      startLabel: t('memory.tracks.start', { name }), endLabel: t('memory.tracks.end', { name }),
    }]
  }),
)
/** Everything the map frames: the shown tracks and the photos. */
const framed = computed<LatLng[]>(() => [...mapTracks.value.flatMap((track) => track.lines.flat()), ...props.photoPoints])
const hasPlaces = computed(() => framed.value.length > 0)
const showMap = computed(() => props.active && mapsAvailable.value && hasPlaces.value)

const map = ref<InstanceType<typeof MemoryMap> | null>(null)
/** Frame the selected track, else everything shown. */
async function frame() {
  await nextTick()
  const chosen = selected.value ? mapTracks.value.find((track) => track.id === selected.value) : null
  map.value?.fitPoints(chosen ? chosen.lines.flat() : framed.value)
}
// The map frames what it shows once it is there and whenever a track is read, shown, hidden or chosen - never on a photo's
// dot alone moving, so a member's pan is not undone by the live list.
watch([showMap, () => mapTracks.value.length, selected], () => {
  if (showMap.value) void frame()
})

// --- add ---------------------------------------------------------------------------------------------------------
const fileInput = ref<HTMLInputElement | null>(null)
const checking = ref(false)
function pick() {
  if (!props.canAdd || !props.memoryKey) return
  if (props.pickReady && !props.pickReady()) return
  fileInput.value?.click()
}
async function onPicked() {
  const input = fileInput.value
  if (!input) return
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return
  checking.value = true
  try {
    const accepted: File[] = []
    for (const file of files) {
      const verdict = isGpxFile(file)
        ? await checkGpxFile(file, props.tracks.length + accepted.length)
        : { problem: 'invalid' as const }
      if ('problem' in verdict) toast.error(t(`memory.tracks.problem.${verdict.problem}`, { name: file.name }))
      else accepted.push(file.type === GPX_MEDIA_TYPE ? file : new File([file], file.name, { type: GPX_MEDIA_TYPE, lastModified: file.lastModified }))
    }
    if (accepted.length) emit('add', accepted)
  } finally {
    checking.value = false
  }
}

// --- a row's menu ------------------------------------------------------------------------------------------------
const rowMenu = ref<InstanceType<typeof VeyqMenu> | null>(null)
const menuFor = ref<TrackEntry | null>(null)
function openRowMenu(event: Event, entry: TrackEntry) {
  menuFor.value = entry
  rowMenu.value?.toggle(event)
}
const rowMenuItems = computed<VeyqMenuItem[]>(() => {
  const entry = menuFor.value
  if (!entry) return []
  return [
    { key: 'download', label: t('memory.tracks.download'), icon: 'download', visible: props.canDownload, command: () => void download(entry) },
    { key: 'report', label: t('report.action'), icon: 'flag', visible: props.canReport, command: () => emit('report', entry.item) },
    { key: 'remove', label: t('memory.tracks.remove'), icon: 'trash', visible: props.canRemove(entry.mediaId), command: () => emit('remove', entry.item) },
  ]
})
function hasRowActions(entry: TrackEntry): boolean {
  return props.canDownload || props.canReport || props.canRemove(entry.mediaId)
}

async function download(entry: TrackEntry) {
  const key = props.memoryKey
  if (!key) return
  try {
    await downloadOriginal(props.loaders.chunk(entry.item, key), originalDownloadName(props.loaders.blobMeta(entry.item, key), props.memoryName, 0))
  } catch (e) {
    if (e instanceof SaveTooLarge) toast.error(t('media.downloadTooLarge'))
    else if (!isSaveCancelled(e)) toast.error(t('memory.tracks.downloadFailed'))
  }
}

function factsOf(entry: TrackEntry): string {
  return entry.stats ? trackFacts(entry.stats, locale.value, t).join(' · ') : ''
}
</script>

<template>
  <div class="memory-tracks">
    <div v-if="canAdd" class="memory-tracks-toolbar">
      <input
        ref="fileInput"
        type="file"
        multiple
        accept=".gpx,application/gpx+xml"
        class="memory-tracks-file"
        tabindex="-1"
        aria-hidden="true"
        data-testid="track-file-input"
        @change="onPicked"
      />
      <VeyqButton
        :label="t('memory.tracks.add')"
        icon="upload"
        variant="secondary"
        appearance="outlined"
        :loading="checking"
        :disabled="!memoryKey"
        data-testid="track-add"
        @click="pick"
      />
    </div>

    <!-- the map: nothing on this server, the consent offer, nothing to show yet, or the photos and the shown tracks -->
    <div v-if="!mapsOffered" class="memory-tracks-panel">
      <VeyqIcon name="map" class="memory-tracks-panel-icon" />
      <p>{{ t('memory.place.mapsNotOffered') }}</p>
    </div>
    <MapConsentPrompt v-else-if="!mapsAvailable" :heading-level="2" />
    <div v-else-if="!hasPlaces" class="memory-tracks-panel">
      <VeyqIcon name="map-marker" class="memory-tracks-panel-icon" />
      <p>{{ t('memory.tracks.nothingOnTheMap') }}</p>
    </div>
    <MemoryMap
      v-else-if="showMap"
      ref="map"
      :tile-url="tileUrl"
      :attribution="tileAttribution"
      :max-zoom="tileMaxZoom"
      :center="WORLD_VIEW.center"
      :zoom="WORLD_VIEW.zoom"
      :dots="photoPoints.slice()"
      :tracks="mapTracks"
      :selected-track="selected"
      @select-track="select"
    />

    <h2 class="memory-tracks-title">{{ t('memory.tracks.title') }}</h2>
    <p v-if="entries.length === 0" class="veyq-empty memory-tracks-empty">
      {{ canAdd ? t('memory.tracks.emptyHint') : t('memory.tracks.empty') }}
    </p>
    <ul v-else class="memory-tracks-list" data-testid="track-list">
      <li
        v-for="(entry, index) in entries"
        :key="entry.mediaId"
        class="memory-track"
        :class="{ 'is-selected': selected === entry.mediaId }"
        data-testid="track-row"
      >
        <VeyqCheckbox
          :model-value="!hidden.has(entry.mediaId)"
          :disabled="entry.status !== 'ready'"
          :aria-label="t('memory.tracks.show', { name: nameOf(entry, index) })"
          @update:model-value="(v: boolean) => setShown(entry.mediaId, v)"
        />
        <span class="memory-track-swatch" :style="{ backgroundColor: entry.color }" aria-hidden="true" />
        <button type="button" class="memory-track-main" :disabled="entry.status !== 'ready'" @click="select(entry.mediaId)">
          <span class="memory-track-name">{{ nameOf(entry, index) }}</span>
          <span v-if="entry.status === 'loading'" class="memory-track-facts">{{ t('memory.tracks.loading') }}</span>
          <span v-else-if="entry.status === 'failed'" class="memory-track-facts is-failed">{{ t('memory.tracks.unreadable') }}</span>
          <span v-else class="memory-track-facts">{{ factsOf(entry) }}</span>
        </button>
        <button
          v-if="hasRowActions(entry)"
          type="button"
          class="veyq-icon-button memory-track-more"
          :aria-label="t('memory.tracks.actions', { name: nameOf(entry, index) })"
          :title="t('memory.tracks.actions', { name: nameOf(entry, index) })"
          data-testid="track-more"
          @click="openRowMenu($event, entry)"
        >
          <VeyqIcon name="ellipsis-h" />
        </button>
      </li>
    </ul>
    <VeyqMenu ref="rowMenu" :items="rowMenuItems" />
  </div>
</template>

<style scoped>
.memory-tracks {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.memory-tracks-toolbar {
  display: flex;
  justify-content: flex-end;
}
.memory-tracks-file {
  display: none;
}
.memory-tracks-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 2rem 1rem;
  border: 1px dashed var(--app-line);
  border-radius: var(--app-radius);
  color: var(--app-muted);
  text-align: center;
}
.memory-tracks-panel-icon {
  font-size: 1.75rem;
}
.memory-tracks-title {
  margin: 0.5rem 0 0;
  font-size: 1rem;
}
.memory-tracks-empty {
  margin: 0;
}
.memory-tracks-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.memory-track {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
}
.memory-track.is-selected {
  border-color: var(--app-accent);
}
.memory-track-swatch {
  flex: none;
  width: 0.9rem;
  height: 0.9rem;
  border-radius: 50%;
}
.memory-track-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.15rem;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: start;
  cursor: pointer;
}
.memory-track-main:disabled {
  cursor: default;
}
.memory-track-name {
  font-weight: 600;
  overflow-wrap: anywhere;
}
.memory-track-facts {
  color: var(--app-muted);
  font-size: 0.875rem;
}
.memory-track-facts.is-failed {
  color: var(--app-danger);
}
</style>
