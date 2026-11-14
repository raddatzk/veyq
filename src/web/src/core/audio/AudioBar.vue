<script setup lang="ts">
// Core's audio bar (en.platform.audio-player, ADR 0135 decision 8): the page's one player, drawn below the shell's footer
// while a queue is loaded - the title, previous, play/pause, next, a progress bar over the trimmed span the listener can
// drag, and close. Mounted ONCE by the shell that draws it - the signed-in app's (`AppShell`, while the session is signed in
// and unlocked) and the share page's (`ShareView`) - so it plays on through every change of page, and **its unmount stops
// the player**, which releases every source the player opened: a lock, a sign-out, leaving a share page. Sticky at the
// viewport's bottom edge, so it is always within reach. When no track of a queue plays here, it says so for a few seconds.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import { audioPlayer, type AudioPlayer } from './player'

const props = defineProps<{ player?: AudioPlayer }>()
const { t } = useI18n()
const player = props.player ?? audioPlayer()
const now = player.nowPlaying

/** How long the bar says that nothing plays before it goes. */
const NOTICE_MS = 5000
const notice = ref(false)
let noticeTimer: ReturnType<typeof setTimeout> | undefined
watch(player.notice, (why) => {
  clearTimeout(noticeTimer)
  notice.value = why === 'unplayable'
  if (notice.value) noticeTimer = setTimeout(() => { notice.value = false; player.notice.value = null }, NOTICE_MS)
})

// The thumb follows the player unless the listener holds it: a drag shows where it will land, the release seeks there.
const dragging = ref<number | null>(null)
const position = computed(() => dragging.value ?? now.value?.positionMs ?? 0)
function onInput(event: Event) {
  dragging.value = Number((event.target as HTMLInputElement).value)
}
function onChange(event: Event) {
  player.seek(Number((event.target as HTMLInputElement).value))
  dragging.value = null
}
function clock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

onBeforeUnmount(() => {
  clearTimeout(noticeTimer)
  player.stop()
})
</script>

<template>
  <section v-if="now || notice" class="veyq-audio-bar" :aria-label="t('audio.bar')" data-testid="audio-bar">
    <template v-if="now">
      <div class="veyq-audio-bar__controls">
        <button type="button" class="veyq-icon-button" :aria-label="t('audio.previous')" :title="t('audio.previous')" data-testid="audio-previous" @click="player.previous()">
          <VeyqIcon name="step-backward" />
        </button>
        <button
          type="button"
          class="veyq-icon-button veyq-audio-bar__toggle"
          :aria-label="now.playing ? t('audio.pause') : t('audio.play')"
          :title="now.playing ? t('audio.pause') : t('audio.play')"
          data-testid="audio-toggle"
          @click="now.playing ? player.pause() : player.resume()"
        >
          <VeyqIcon :name="now.playing ? 'pause' : 'play'" />
        </button>
        <button type="button" class="veyq-icon-button" :aria-label="t('audio.next')" :title="t('audio.next')" data-testid="audio-next" @click="player.next()">
          <VeyqIcon name="step-forward" />
        </button>
      </div>
      <div class="veyq-audio-bar__track">
        <p class="veyq-audio-bar__title" data-testid="audio-title">{{ now.title }}<span v-if="now.album" class="veyq-audio-bar__album"> · {{ now.album }}</span></p>
        <div class="veyq-audio-bar__progress">
          <span class="veyq-audio-bar__time">{{ clock(position) }}</span>
          <input
            type="range"
            min="0"
            :max="now.durationMs || 0"
            step="100"
            :value="position"
            :disabled="!now.durationMs"
            :aria-label="t('audio.progress', { title: now.title })"
            :aria-valuetext="`${clock(position)} / ${clock(now.durationMs)}`"
            data-testid="audio-progress"
            @input="onInput"
            @change="onChange"
          />
          <span class="veyq-audio-bar__time">{{ clock(now.durationMs) }}</span>
        </div>
      </div>
      <button type="button" class="veyq-icon-button" :aria-label="t('audio.close')" :title="t('audio.close')" data-testid="audio-close" @click="player.stop()">
        <VeyqIcon name="times" />
      </button>
    </template>
    <p v-else class="veyq-audio-bar__notice" role="status" data-testid="audio-notice">
      <VeyqIcon name="music" /> {{ t('audio.nothingPlays') }}
    </p>
  </section>
</template>

<style scoped>
/* The bar's place and look are core's: in the page's flow after the footer, sticky at the viewport's bottom edge, above
   the page's own sticky rows (a Memory's tab row) and below a dialog's layer; the app's surface and line. */
.veyq-audio-bar {
  position: sticky;
  bottom: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 0.5rem 0.75rem;
  min-height: 3.5rem;
  padding: 0.4rem 1rem;
  border-top: 1px solid var(--app-line);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-md);
}
.veyq-audio-bar__controls {
  display: flex;
  flex: none;
  align-items: center;
  gap: 0.25rem;
}
.veyq-audio-bar__track {
  display: grid;
  flex: 1 1 auto;
  min-width: 0;
  gap: 0.1rem;
}
.veyq-audio-bar__title {
  overflow: hidden;
  margin: 0;
  font-weight: 600;
  font-size: 0.9rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.veyq-audio-bar__album {
  color: var(--app-muted);
  font-weight: 400;
}
.veyq-audio-bar__progress {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 0.5rem;
}
.veyq-audio-bar__progress input {
  width: 100%;
  accent-color: var(--app-accent);
}
.veyq-audio-bar__time {
  color: var(--app-muted);
  font-size: 0.78rem;
  font-variant-numeric: tabular-nums;
}
.veyq-audio-bar__notice {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0;
  color: var(--app-muted);
  font-size: 0.86rem;
}
@media (max-width: 720px) {
  .veyq-audio-bar {
    padding: 0.4rem 0.75rem;
  }
}
</style>
