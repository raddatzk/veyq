<script setup lang="ts">
// The music bar (uc.memory.music, ADR 0131 decision 5): Memories' footer bar, the one the shell mounts below its footer on
// every page (core's `app.footerBar` slot, `core/footerBar/FooterBars.vue`) - the signed-in app's and the share page's
// alike (share view = member view). It draws nothing until a viewer starts a playlist from a Memory's or a chapter's header
// (`musicPlayback.ts`), and since the shell, not the page, owns it, the music plays on while the viewer moves through veyq.
// What it shows for that playlist is what this server and this browser allow:
//  - this browser has not agreed to the service: the opt-in, naming the service, the music host and what the service
//    learns, with "Play here" - nothing is loaded before that click - the link that opens the playlist in the service's
//    own app (which loads nothing into veyq), and Close;
//  - agreed: the music frame, an iframe of the music host (another origin: no vendor code ever runs in this page, which
//    holds the viewer's keys), handed the playlist, the service's public credential and the app's theme by `postMessage`
//    once it says `ready`; a playlist of the same service started later goes to the same frame, which switches and keeps
//    the vendor's sign-in, another service's gets a new frame. Beside it "Turn music off", which withdraws the consent,
//    tells the frame to forget the vendor's sign-in and keeps it until it said so (never longer than FORGET_WAIT_MS) - a
//    withdrawal in another tab removes it at once -, and Close, which ends the music and keeps the consent.
// The frame's messages are taken only from that iframe's window and the music host's origin, and parsed against the
// protocol (`src/music-frame/protocol.ts`). The bar ends what it plays when the shell unmounts it: a sign-out, a lock,
// leaving the share page.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { useThemeState } from '../../../shared/state/useThemeState'
import { parseFrameMessage, playlistLink, type AppMessage } from '../../../music-frame/protocol'
import type { MusicService, Playlist } from './musicModel'
import { setMusicConsent, useMusicConsent } from './useMusicConsent'
import { closeBar, playlistInBar } from './musicPlayback'

const { t, locale } = useI18n()
const consent = useMusicConsent()
const { resolvedTheme } = useThemeState()

/** How long a withdrawal waits for the frame's `forgotten` before it removes the frame anyway. */
const FORGET_WAIT_MS = 1500

const playlist = computed<Playlist | null>(() => playlistInBar.value)
/** The service whose sign-in the frame is forgetting: the frame stays until it said so, the consent is already gone. */
const forgetting = ref<MusicService | null>(null)
let forgetTimer: ReturnType<typeof setTimeout> | null = null

/** The service the frame plays: the bar's playlist's, while the server plays it here and this browser agreed. */
const frameService = computed<MusicService | null>(() => {
  if (forgetting.value) return forgetting.value
  const p = playlist.value
  return p && consent.offered(p.service) && consent.consented(p.service) ? p.service : null
})
/** The opt-in is asked: a playlist the server plays here, which this browser has not agreed to. */
const asking = computed(() => {
  const p = playlist.value
  return p !== null && !forgetting.value && consent.offered(p.service) && !consent.consented(p.service)
})
const serviceName = (s: MusicService) => t(`memory.music.service.${s}`)

// A playlist the server stops playing here (switched off, a new deployment) leaves the bar: the header offers its link.
watch(
  () => playlist.value !== null && !consent.offered(playlist.value.service),
  (gone) => { if (gone) closeBar() },
  { immediate: true },
)

const frame = ref<HTMLIFrameElement | null>(null)
/** Whether the mounted frame said `ready`: a new frame (another service) has to say it again. */
const ready = ref(false)
watch(frameService, () => (ready.value = false))

function send(message: AppMessage) {
  frame.value?.contentWindow?.postMessage(message, consent.frameOrigin.value)
}
function load(p: Playlist) {
  send({
    type: 'veyq-music:load', playlist: { ...p }, credential: consent.credential(p.service),
    locale: locale.value === 'de' ? 'de' : 'en', theme: resolvedTheme.value,
  })
}

// Another playlist of the frame's service, started from another header: the same frame switches to it.
watch(playlist, (p, before) => {
  if (p && ready.value && before && p.service === before.service && p.service === frameService.value && p.id !== before.id) load(p)
})
watch(resolvedTheme, (theme) => { if (ready.value) send({ type: 'veyq-music:theme', theme }) })

function playHere() {
  if (playlist.value) setMusicConsent(playlist.value.service, true)
}

function endForgetting() {
  if (forgetTimer) clearTimeout(forgetTimer)
  forgetTimer = null
  forgetting.value = null
  closeBar()
}

function turnOff() {
  const service = frameService.value
  if (!service) return
  forgetting.value = service
  setMusicConsent(service, false)
  send({ type: 'veyq-music:forget' })
  forgetTimer = setTimeout(endForgetting, FORGET_WAIT_MS)
}

function close() {
  if (forgetting.value) return endForgetting()
  closeBar()
}

function onMessage(e: MessageEvent) {
  if (!frame.value || e.source !== frame.value.contentWindow || e.origin !== consent.frameOrigin.value) return
  const m = parseFrameMessage(e.data)
  if (!m) return
  if (m.type === 'veyq-music:ready') {
    ready.value = true
    if (playlist.value && !forgetting.value) load(playlist.value)
  } else if (m.type === 'veyq-music:forgotten' && forgetting.value) {
    endForgetting()
  }
}
window.addEventListener('message', onMessage)
onBeforeUnmount(() => {
  window.removeEventListener('message', onMessage)
  if (forgetTimer) clearTimeout(forgetTimer)
  closeBar()
})
</script>

<template>
  <section
    v-if="playlist && (asking || frameService)"
    class="music-bar"
    :class="{ 'music-bar--asking': asking }"
    :aria-label="t('memory.music.title')"
    data-testid="music-bar"
    :data-service="playlist.service"
  >
    <template v-if="asking">
      <VeyqIcon name="music" class="music-bar__icon" />
      <p class="music-bar__consent" data-testid="music-consent">
        {{ t('memory.music.consentBody', { service: serviceName(playlist.service), host: consent.frameHost.value }) }}
      </p>
      <div class="music-bar__actions">
        <VeyqButton :label="t('memory.music.playHere')" icon="play" size="small" data-testid="music-play-here" @click="playHere" />
        <a class="music-bar__open" :href="playlistLink(playlist)" target="_blank" rel="noopener noreferrer" data-testid="music-open">
          <VeyqIcon name="external-link" /> {{ t('memory.music.open', { service: serviceName(playlist.service) }) }}
        </a>
      </div>
    </template>
    <template v-else-if="frameService">
      <iframe
        :key="frameService"
        ref="frame"
        class="music-bar__frame"
        :src="consent.frameUrl.value"
        :title="t('memory.music.frameTitle', { service: serviceName(frameService) })"
        allow="encrypted-media; autoplay"
        data-testid="music-frame"
      />
      <button
        type="button"
        class="veyq-icon-button music-bar__off"
        :aria-label="t('memory.music.off')"
        :title="t('memory.music.off')"
        :disabled="forgetting !== null"
        data-testid="music-off"
        @click="turnOff"
      >
        <VeyqIcon name="power-off" />
      </button>
    </template>
    <button
      type="button"
      class="veyq-icon-button music-bar__close"
      :aria-label="t('memory.music.close')"
      :title="t('memory.music.close')"
      data-testid="music-close"
      @click="close"
    >
      <VeyqIcon name="times" />
    </button>
  </section>
</template>

<style scoped>
/* veyq's chrome, not a vendor's: the app's surface and line, the frame inside drawn in the same tokens. */
.music-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem 0.75rem;
  min-height: 3.5rem;
  padding: 0.4rem 1rem;
  border-top: 1px solid var(--app-line);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-md);
}
.music-bar__icon {
  flex: none;
  color: var(--app-muted);
}
.music-bar__consent {
  flex: 1 1 18rem;
  min-width: 0;
  margin: 0;
  color: var(--app-muted);
  font-size: 0.86rem;
}
.music-bar__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
}
.music-bar__open {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  color: var(--app-primary);
  font-size: 0.86rem;
}
.music-bar__frame {
  flex: 1 1 auto;
  min-width: 0;
  height: 3.5rem;
  border: 0;
  /* the frame's page uses the app's scheme (its theme follows the app's), so its transparent page shows this bar's
     surface instead of a backdrop the browser paints under a frame of another scheme */
  color-scheme: inherit;
}
.music-bar__off,
.music-bar__close {
  flex: none;
}
@media (max-width: 720px) {
  .music-bar {
    padding: 0.4rem 0.75rem;
  }
  /* the opt-in's sentence takes a line of its own; the player's row never wraps */
  .music-bar--asking {
    flex-wrap: wrap;
  }
}
</style>
