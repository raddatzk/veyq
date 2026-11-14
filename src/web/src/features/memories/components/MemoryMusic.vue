<script setup lang="ts">
// A Memory's or a chapter's Music tab (uc.memory.background-music, ADR 0135 decision 9): its music tracks with their titles
// and lengths. A track is a medium (decision 2), handed in as `music` by the gallery's listing; its title is its file's name
// without the extension, its length the one sealed at its upload (`audioFacts.ts`) - nothing is read of a track to list it.
// A member and a share guest see the same tab, with the actions the detail's rights grant (`canAdd`, `canRemove`,
// `canDownload`, `canReport`) - nothing here checks a role.
//
// Adding checks each picked file on the device, one after the other (`checkAudioFile`: its size, an audio container by its
// bytes, a decode for its facts - one decode at a time, for the memory a long file costs), names a refused one in a toast
// (E1), and hands the accepted ones with their type and facts to the detail, which sends them through the one upload queue
// and seals the facts at birth (`add`). Pressing a track starts the node's music with it (`play`, A1).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import type { VeyqMenuItem } from '../../../shared/ui/menu'
import { useToast } from '../../../shared/state/useToast'
import { SaveTooLarge, isSaveCancelled } from '../../../shared/saveStream'
import type { MediaItem, RestoredMediaFacts } from '../media'
import type { MediaLoaders } from '../source'
import { downloadOriginal, originalDownloadName } from '../download'
import { checkAudioFile, formatTrackLength, musicTitle } from '../music/audio'
import { audioFactsFrom } from '../music/audioFacts'

const props = defineProps<{
  memoryName: string
  memoryKey: Uint8Array | null
  /** The node's music tracks (`useMediaGalleryLive`'s `music`). */
  music: readonly MediaItem[]
  loaders: MediaLoaders
  canAdd: boolean
  /** Whether the track `mediaId` may be removed here (a member's any, a guest's own). */
  canRemove: (mediaId: string) => boolean
  canDownload: boolean
  canReport: boolean
  /** The track playing now, if this node's music plays: it is marked in the list. */
  playingId?: string | null
  /** Asked before the picker opens: false when the session must get ready first (a guest names itself). */
  pickReady?: () => boolean
}>()
const emit = defineEmits<{
  /** Files checked as music, each with the type its bytes name and its facts, for the upload queue. */
  add: [selections: { file: File; mediaType: string; facts: RestoredMediaFacts }[]]
  remove: [item: MediaItem]
  report: [item: MediaItem]
  /** Start the node's music with this track first (A1). */
  play: [mediaId: string]
}>()

const { t } = useI18n()
const toast = useToast()

/** A listed track: its title (its file's name, else its place in the list) and its length, where its facts say it. */
type MusicEntry = { item: MediaItem; mediaId: string; title: string; length: string | null }
const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
const entries = computed<MusicEntry[]>(() => {
  const key = props.memoryKey
  if (!key) return []
  const named = props.music.map((item) => {
    const facts = audioFactsFrom(props.loaders.meta(item, key))
    return { item, mediaId: item.mediaId, own: musicTitle(props.loaders.blobMeta(item, key)?.name), length: facts ? formatTrackLength(facts.durationMs) : null }
  })
  named.sort((a, b) => (a.own === null ? 1 : b.own === null ? -1 : collator.compare(a.own, b.own)))
  return named.map(({ own, ...e }, i) => ({ ...e, title: own ?? t('memory.music.untitled', { n: i + 1 }) }))
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
    const accepted: { file: File; mediaType: string; facts: RestoredMediaFacts }[] = []
    for (const file of files) {
      const verdict = await checkAudioFile(file)
      if ('problem' in verdict) toast.error(t(`memory.music.problem.${verdict.problem}`, { name: file.name }))
      else accepted.push({ file: verdict.file, mediaType: verdict.mediaType, facts: verdict.facts })
    }
    if (accepted.length) emit('add', accepted)
  } finally {
    checking.value = false
  }
}

// --- a row's menu ------------------------------------------------------------------------------------------------
const rowMenu = ref<InstanceType<typeof VeyqMenu> | null>(null)
const menuFor = ref<MusicEntry | null>(null)
function openRowMenu(event: Event, entry: MusicEntry) {
  menuFor.value = entry
  rowMenu.value?.toggle(event)
}
const rowMenuItems = computed<VeyqMenuItem[]>(() => {
  const entry = menuFor.value
  if (!entry) return []
  return [
    { key: 'download', label: t('memory.music.download'), icon: 'download', visible: props.canDownload, command: () => void download(entry) },
    { key: 'report', label: t('report.action'), icon: 'flag', visible: props.canReport, command: () => emit('report', entry.item) },
    { key: 'remove', label: t('memory.music.remove'), icon: 'trash', visible: props.canRemove(entry.mediaId), command: () => emit('remove', entry.item) },
  ]
})
function hasRowActions(entry: MusicEntry): boolean {
  return props.canDownload || props.canReport || props.canRemove(entry.mediaId)
}

async function download(entry: MusicEntry) {
  const key = props.memoryKey
  if (!key) return
  try {
    await downloadOriginal(props.loaders.chunk(entry.item, key), originalDownloadName(props.loaders.blobMeta(entry.item, key), props.memoryName, 0))
  } catch (e) {
    if (e instanceof SaveTooLarge) toast.error(t('media.downloadTooLarge'))
    else if (!isSaveCancelled(e)) toast.error(t('memory.music.downloadFailed'))
  }
}
</script>

<template>
  <div class="memory-music">
    <div v-if="canAdd" class="memory-music-toolbar">
      <input
        ref="fileInput"
        type="file"
        multiple
        accept="audio/*"
        class="memory-music-file"
        tabindex="-1"
        aria-hidden="true"
        data-testid="music-file-input"
        @change="onPicked"
      />
      <VeyqButton
        :label="t('memory.music.add')"
        icon="upload"
        variant="secondary"
        appearance="outlined"
        :loading="checking"
        :disabled="!memoryKey"
        data-testid="music-add"
        @click="pick"
      />
    </div>

    <h2 class="memory-music-title">{{ t('memory.music.title') }}</h2>
    <p v-if="entries.length === 0" class="veyq-empty memory-music-empty">
      {{ canAdd ? t('memory.music.emptyHint') : t('memory.music.empty') }}
    </p>
    <ul v-else class="memory-music-list" data-testid="music-list">
      <li
        v-for="entry in entries"
        :key="entry.mediaId"
        class="memory-music-track"
        :class="{ 'is-playing': playingId === entry.mediaId }"
        data-testid="music-row"
      >
        <button
          type="button"
          class="memory-music-main"
          :aria-label="t('memory.music.playFrom', { name: entry.title })"
          :aria-current="playingId === entry.mediaId ? 'true' : undefined"
          data-testid="music-play"
          @click="emit('play', entry.mediaId)"
        >
          <VeyqIcon :name="playingId === entry.mediaId ? 'volume-up' : 'play'" class="memory-music-icon" />
          <span class="memory-music-name" data-testid="music-title">{{ entry.title }}</span>
          <span v-if="entry.length" class="memory-music-length" data-testid="music-length">{{ entry.length }}</span>
        </button>
        <button
          v-if="hasRowActions(entry)"
          type="button"
          class="veyq-icon-button memory-music-more"
          :aria-label="t('memory.music.actions', { name: entry.title })"
          :title="t('memory.music.actions', { name: entry.title })"
          data-testid="music-more"
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
/* The track list's look (`MemoryTracks.vue`): one bordered row per track, its press the main button, its menu at the end. */
.memory-music {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.memory-music-toolbar {
  display: flex;
  justify-content: flex-end;
}
.memory-music-file {
  display: none;
}
.memory-music-title {
  margin: 0.5rem 0 0;
  font-size: 1rem;
}
.memory-music-empty {
  margin: 0;
}
.memory-music-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.memory-music-track {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
}
.memory-music-track.is-playing {
  border-color: var(--app-accent);
}
.memory-music-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: start;
  cursor: pointer;
}
.memory-music-icon {
  flex: none;
  color: var(--app-muted);
}
.memory-music-track.is-playing .memory-music-icon {
  color: var(--app-accent);
}
.memory-music-name {
  flex: 1;
  min-width: 0;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.memory-music-length {
  flex: none;
  color: var(--app-muted);
  font-size: 0.875rem;
  font-variant-numeric: tabular-nums;
}
</style>
