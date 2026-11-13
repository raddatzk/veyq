// The viewer's consent to background music (ADR 0131 decision 5), and what this server offers for it. Mirrors the map's
// consent (`shared/state/useMapConfig.ts`, ADR 0016): one per BROWSER (device-local, no server state), off until given,
// given and withdrawn at the Memory's music bar - the one place it is asked - and a share guest gives it there too.
//
// **Per service, naming its recipients.** The key stores, as JSON, the music host the consent was given for and the
// services it covers. A service's consent holds only while that host is the one the server serves now: a new music host
// asks again (the frame is a recipient of what the vendor's player does, the vendor the other), and nothing loads from
// it before the viewer agrees. Withdrawing a service removes it at once; the bar then tells the frame to forget the
// vendor's sign-in and unmounts it. Another tab of this browser follows through the `storage` event.
//
// **What the server offers** comes from the capabilities it carries (ADR 0131 decision 4): `memories.music` with the
// frame's address as `serviceUrl`, `memories.music.spotify` with the Spotify client ID, `memories.music.apple-music` with
// the MusicKit developer token - each present only while configured and switched on (the server ANDs the path).
import { computed, readonly, ref } from 'vue'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { serviceHost } from '../../../shared/state/useMapConfig'
import { MEMORIES_MUSIC, MEMORIES_MUSIC_APPLE_MUSIC, MEMORIES_MUSIC_SPOTIFY } from '../../platform/capabilities'
import { MUSIC_SERVICES, type MusicService } from './musicModel'

const CONSENT_KEY = 'veyq:music-consent'

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // storage disabled: the consent lives as long as the page
  }
}

/** A stored consent: the music host it named and the services it covers. */
export interface MusicConsent {
  host: string
  services: MusicService[]
}

function readConsent(): MusicConsent | null {
  const raw = storage()?.getItem(CONSENT_KEY)
  if (raw == null) return null
  try {
    const v = JSON.parse(raw) as Partial<MusicConsent> | null
    if (v && typeof v.host === 'string' && Array.isArray(v.services)) {
      return { host: v.host, services: MUSIC_SERVICES.filter((s) => v.services!.includes(s)) }
    }
  } catch {
    /* not ours: no consent */
  }
  return null
}

const given = ref<MusicConsent | null>(readConsent())
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === CONSENT_KEY || e.key === null) given.value = readConsent()
  })
}

const { capabilityValues } = useCapabilities()
/** The music frame's address while `memories.music` is served, else ''. */
const frameUrl = computed(() => capabilityValues(MEMORIES_MUSIC)?.serviceUrl ?? '')
const frameHost = computed(() => (frameUrl.value ? serviceHost(frameUrl.value) : null))
/** Each service's public credential while its entry is served (the parent's AND is the server's). */
const credentials = computed<Record<MusicService, string>>(() => ({
  spotify: capabilityValues(MEMORIES_MUSIC_SPOTIFY)?.spotifyClientId ?? '',
  'apple-music': capabilityValues(MEMORIES_MUSIC_APPLE_MUSIC)?.appleDeveloperToken ?? '',
}))

function persist(next: MusicConsent | null): void {
  given.value = next
  if (next && next.services.length > 0) storage()?.setItem(CONSENT_KEY, JSON.stringify(next))
  else storage()?.removeItem(CONSENT_KEY)
}

/** Give (`true`) or withdraw (`false`) this browser's consent for [service] at the music host served now. A consent for
 *  another host is replaced, never extended: it named a recipient that is no longer this one. */
export function setMusicConsent(service: MusicService, on: boolean): void {
  const host = frameHost.value
  const current = given.value && given.value.host === host ? given.value.services : []
  const services = on ? MUSIC_SERVICES.filter((s) => s === service || current.includes(s)) : current.filter((s) => s !== service)
  persist(host ? { host, services } : null)
}

export function useMusicConsent() {
  return {
    frameUrl,
    consentGiven: readonly(given),
    /** The frame's origin, as `postMessage` checks it; '' while none is served. */
    frameOrigin: computed(() => {
      try {
        return frameUrl.value ? new URL(frameUrl.value).origin : ''
      } catch {
        return ''
      }
    }),
    frameHost,
    /** Whether this server plays [service] here: the frame and the service's credential are served. */
    offered: (service: MusicService) => Boolean(frameUrl.value && credentials.value[service]),
    credential: (service: MusicService) => credentials.value[service],
    /** Whether this browser agreed to [service] at the music host served now. */
    consented: (service: MusicService) =>
      Boolean(given.value && frameHost.value && given.value.host === frameHost.value && given.value.services.includes(service)),
  }
}
