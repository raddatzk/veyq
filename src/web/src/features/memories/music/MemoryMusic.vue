<script setup lang="ts">
// The music bar (uc.memory.music, ADR 0131): above a Memory's or a chapter's tabs while its sealed meta holds a playlist,
// the same for a member and a share guest (share view = member view). Per playlist it offers what this server and this
// browser allow:
//  - the server plays the service here (`useMusicConsent().offered`) and this browser has not agreed: the opt-in, naming
//    the service, the music host and what the service learns, with "Play here" - nothing is loaded before that click;
//  - agreed: the music frame, an iframe of the music host (another origin: no vendor code ever runs in this page, which
//    holds the viewer's keys), handed the playlist and the service's public credential by `postMessage` once it says
//    `ready`, and "Turn music off", which withdraws the consent, tells the frame to forget the vendor's sign-in and
//    removes it (A1) - a withdrawal in another tab removes it too;
//  - always: the link that opens the playlist in the service's own app or site, which loads nothing into veyq (A3).
// One frame at a time: a Memory with both services plays the one the viewer chose. The frame's messages are taken only
// from that iframe's window and the music host's origin, and parsed against the protocol (`src/music-frame/protocol.ts`).
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { parseFrameMessage, playlistLink, type AppMessage } from '../../../music-frame/protocol'
import type { MusicService, Playlist } from './musicModel'
import { setMusicConsent, useMusicConsent } from './useMusicConsent'

const props = defineProps<{ music: readonly Playlist[] }>()
const { t, locale } = useI18n()
const consent = useMusicConsent()

/** How long a withdrawal waits for the frame's `forgotten` before it removes the frame anyway. */
const FORGET_WAIT_MS = 1500

const frame = ref<HTMLIFrameElement | null>(null)
/** The one frame's element (a function ref: an element ref inside `v-for` would be a list). */
function setFrame(el: unknown) {
  frame.value = el instanceof HTMLIFrameElement ? el : null
}
/** The service whose frame is mounted, null for none. */
const playing = ref<MusicService | null>(null)
let forgetting: ReturnType<typeof setTimeout> | null = null

/** The first playlist this browser already agreed to - standing consent, as the map's: its frame mounts at once. */
function firstConsented(): MusicService | null {
  return props.music.find((p) => consent.offered(p.service) && consent.consented(p.service))?.service ?? null
}
watch(
  () => [props.music, consent.consentGiven.value, consent.frameUrl.value] as const,
  () => {
    if (playing.value && !(consent.offered(playing.value) && consent.consented(playing.value))) unmount()
    if (!playing.value) playing.value = firstConsented()
  },
  { immediate: true, deep: true },
)

const current = computed(() => props.music.find((p) => p.service === playing.value) ?? null)
const serviceName = (s: MusicService) => t(`memory.music.service.${s}`)

function playHere(service: MusicService) {
  setMusicConsent(service, true)
  if (playing.value !== service) {
    unmount()
    playing.value = service
  }
}

function send(message: AppMessage) {
  frame.value?.contentWindow?.postMessage(message, consent.frameOrigin.value)
}

function unmount() {
  if (forgetting) clearTimeout(forgetting)
  forgetting = null
  playing.value = null
}

function turnOff(service: MusicService) {
  setMusicConsent(service, false)
  send({ type: 'veyq-music:forget' })
  // the watch above removes the frame as the consent goes; until then it waits for `forgotten`, never forever
  forgetting = setTimeout(unmount, FORGET_WAIT_MS)
}

function onMessage(e: MessageEvent) {
  if (!frame.value || e.source !== frame.value.contentWindow || e.origin !== consent.frameOrigin.value) return
  const m = parseFrameMessage(e.data)
  if (!m) return
  const p = current.value
  if (m.type === 'veyq-music:ready' && p) {
    send({ type: 'veyq-music:load', playlist: { ...p }, credential: consent.credential(p.service), locale: locale.value === 'de' ? 'de' : 'en' })
  } else if (m.type === 'veyq-music:forgotten') {
    unmount()
  }
}
window.addEventListener('message', onMessage)
onBeforeUnmount(() => {
  window.removeEventListener('message', onMessage)
  unmount()
})

defineExpose({ playing })
</script>

<template>
  <section v-if="music.length > 0" class="memory-music" :aria-label="t('memory.music.title')" data-testid="memory-music">
    <h2 class="memory-music__title"><VeyqIcon name="music" /> {{ t('memory.music.title') }}</h2>
    <div v-for="p in music" :key="p.service" class="memory-music__row" :data-service="p.service">
      <template v-if="playing === p.service">
        <iframe
          :ref="setFrame"
          class="memory-music__frame"
          :src="consent.frameUrl.value"
          :title="t('memory.music.frameTitle', { service: serviceName(p.service) })"
          allow="encrypted-media; autoplay"
          data-testid="music-frame"
        />
        <VeyqButton
          :label="t('memory.music.off')" icon="eye-slash" size="small" variant="secondary" appearance="outlined"
          data-testid="music-off" @click="turnOff(p.service)"
        />
      </template>
      <template v-else>
        <span class="memory-music__service">{{ serviceName(p.service) }}</span>
        <template v-if="consent.offered(p.service)">
          <p v-if="!consent.consented(p.service)" class="memory-music__consent" data-testid="music-consent">
            {{ t('memory.music.consentBody', { service: serviceName(p.service), host: consent.frameHost.value }) }}
          </p>
          <VeyqButton :label="t('memory.music.playHere')" icon="play" size="small" data-testid="music-play-here" @click="playHere(p.service)" />
        </template>
      </template>
      <a class="memory-music__open" :href="playlistLink(p)" target="_blank" rel="noopener noreferrer" data-testid="music-open">
        <VeyqIcon name="external-link" /> {{ t('memory.music.open', { service: serviceName(p.service) }) }}
      </a>
    </div>
  </section>
</template>

<style scoped>
.memory-music {
  display: grid;
  gap: 0.5rem;
  margin: 0 0 0.75rem;
  padding: 0.6rem 0.8rem;
  border: 1px solid var(--app-line);
  border-radius: 0.6rem;
  background: var(--app-surface);
}
.memory-music__title {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  margin: 0;
  font-size: 0.95rem;
}
.memory-music__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
}
.memory-music__service {
  font-weight: 600;
}
.memory-music__consent {
  flex: 1 1 18rem;
  margin: 0;
  color: var(--app-muted);
  font-size: 0.86rem;
}
.memory-music__frame {
  flex: 1 1 20rem;
  min-width: 0;
  height: 4.5rem;
  border: 0;
  color-scheme: normal;
}
.memory-music__open {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  color: var(--app-primary);
  font-size: 0.86rem;
}
</style>
