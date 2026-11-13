// The music frame's words (ADR 0131). The frame is a page of its own origin and loads none of the app's code, its i18n
// included, so its few strings live here, in the two languages the app speaks; the app hands the locale with `load`.
import type { FrameError, Locale, MusicService } from './protocol'

const en = {
  service: { spotify: 'Spotify', 'apple-music': 'Apple Music' } as Record<MusicService, string>,
  signIn: (service: string) => `Sign in with ${service}`,
  play: 'Play',
  pause: 'Pause',
  next: 'Next track',
  loading: 'Loading the player…',
  signedOut: (service: string) => `Sign in to hear this playlist with your ${service} account.`,
  ready: 'Press play to start.',
  playing: 'Playing',
  paused: 'Paused',
  open: (service: string) => `Open in ${service}`,
  error: {
    'premium-required': 'Playing here needs Spotify Premium. You can still open the playlist in Spotify.',
    'subscription-required': 'Playing here needs an Apple Music subscription. You can still open the playlist in Apple Music.',
    'sign-in-failed': 'The sign-in did not work. Try again, or open the playlist in the app.',
    unavailable: 'The player could not be loaded here. You can still open the playlist in the app.',
  } as Record<FrameError, string>,
}

const de: typeof en = {
  service: { spotify: 'Spotify', 'apple-music': 'Apple Music' },
  signIn: (service) => `Mit ${service} anmelden`,
  play: 'Abspielen',
  pause: 'Pause',
  next: 'Nächster Titel',
  loading: 'Der Player wird geladen …',
  signedOut: (service) => `Melde dich an, um diese Playlist mit deinem ${service}-Konto zu hören.`,
  ready: 'Drücke auf Abspielen, um zu starten.',
  playing: 'Spielt',
  paused: 'Pausiert',
  open: (service) => `In ${service} öffnen`,
  error: {
    'premium-required': 'Hier abzuspielen braucht Spotify Premium. Die Playlist lässt sich weiter in Spotify öffnen.',
    'subscription-required': 'Hier abzuspielen braucht ein Apple-Music-Abo. Die Playlist lässt sich weiter in Apple Music öffnen.',
    'sign-in-failed': 'Die Anmeldung hat nicht geklappt. Versuche es noch einmal oder öffne die Playlist in der App.',
    unavailable: 'Der Player ließ sich hier nicht laden. Die Playlist lässt sich weiter in der App öffnen.',
  },
}

export type FrameStrings = typeof en

export function stringsFor(locale: Locale): FrameStrings {
  return locale === 'de' ? de : en
}
