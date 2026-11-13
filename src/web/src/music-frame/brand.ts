// The vendors' marks the music frame attributes their content with (ADR 0131 decision 6). The frame draws its own player
// in veyq's look, so what the vendors require of a player that shows their content is the attribution, nothing more
// (read 2026-11-13, the sources in the ADR):
//  - Spotify's Developer Terms (III.1.1) bind an app to its Design Guidelines, which ask that content from Spotify -
//    a track's name, its artists, its cover - is always attributed with Spotify's LOGO (the icon alone where space is
//    tight, at least 21 px), next to it, with a link back to Spotify ("Listen on Spotify"); its cover with rounded corners,
//    uncropped and with nothing drawn over it; a control Spotify restricts not offered.
//  - Apple's Apple Music Identity Guidelines name the Apple Music icon (only Apple's own files, never a redrawn one) for
//    linking to music content inside an app where the badge does not fit, always with a link to Apple Music.
// Both marks must be the vendors' own files, which are not in this repository: until one is added here the frame shows
// NOTHING the vendor attributes for that service - no title, no artist, no cover - only its own state and controls and the
// link back (inbox #857). Adding one: the vendor's file beside this module (`./brand/spotify-icon.svg`), imported by URL
// (`import spotifyIcon from './brand/spotify-icon.svg?url'`) into the entry below with its drawn size.
import type { MusicService } from './protocol'

/** A vendor's mark as the frame draws it: its file's URL and the CSS size the vendor's minimum allows. */
export interface BrandMark {
  src: string
  width: number
  height: number
}

export const BRAND_MARKS: Readonly<Record<MusicService, BrandMark | null>> = Object.freeze({
  spotify: null,
  'apple-music': null,
})
