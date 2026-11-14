<script setup lang="ts">
// Core's compact audio controls (en.platform.audio-player): what plays and previous / play-pause / next, for a surface that
// covers the shell's audio bar - Memories' lightbox draws them in its top bar. They drive the one player the bar drives and
// draw nothing while no queue is loaded. `data-audio-controls` marks them, so a surface that pauses on any press (the
// lightbox's slideshow) can leave them out.
import { useI18n } from 'vue-i18n'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import { audioPlayer, type AudioPlayer } from './player'

const props = defineProps<{ player?: AudioPlayer; buttonClass?: string }>()
const { t } = useI18n()
const player = props.player ?? audioPlayer()
const now = player.nowPlaying
</script>

<template>
  <div v-if="now" class="veyq-audio-mini" data-audio-controls data-testid="audio-mini" :aria-label="t('audio.bar')" role="group">
    <span class="veyq-audio-mini__title" data-testid="audio-mini-title" :title="now.title">{{ now.title }}</span>
    <button type="button" :class="['veyq-icon-button', buttonClass]" :aria-label="t('audio.previous')" :title="t('audio.previous')" data-testid="audio-mini-previous" @click="player.previous()">
      <VeyqIcon name="step-backward" />
    </button>
    <button
      type="button"
      :class="['veyq-icon-button', buttonClass]"
      :aria-label="now.playing ? t('audio.pause') : t('audio.play')"
      :title="now.playing ? t('audio.pause') : t('audio.play')"
      data-testid="audio-mini-toggle"
      @click="now.playing ? player.pause() : player.resume()"
    >
      <VeyqIcon :name="now.playing ? 'pause' : 'play'" />
    </button>
    <button type="button" :class="['veyq-icon-button', buttonClass]" :aria-label="t('audio.next')" :title="t('audio.next')" data-testid="audio-mini-next" @click="player.next()">
      <VeyqIcon name="step-forward" />
    </button>
  </div>
</template>

<style scoped>
.veyq-audio-mini {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 0.5rem;
}
.veyq-audio-mini__title {
  overflow: hidden;
  min-width: 0;
  max-width: 16rem;
  color: var(--app-muted);
  font-size: 0.85rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 720px) {
  .veyq-audio-mini__title {
    display: none;
  }
}
</style>
