// The Memory's (or chapter's) header buttons for its background music (uc.memory.music, ADR 0131 decision 5): one per
// playlist the node names, for every viewer of its page - a member's and a share guest's alike (share view = member view;
// the matrix grants listening wherever the page opens, `docs/apps/memories.md`). What each does is what this server
// offers for its service:
//  - the server plays it here: a toggle that starts the playlist in the music bar below the shell's footer
//    (`musicPlayback.ts`), which asks this browser's opt-in there on first use; pressed while that playlist is the bar's,
//    and pressing it then ends it. Opening another Memory changes nothing in the bar until its own button is pressed;
//  - it does not (no music host, the service unconfigured or switched off): it opens the playlist in the service's own app
//    or site - a link the viewer follows, which loads nothing into veyq (A3).
// Their ids sit in the kind's `header.order` (`register.ts`); setting the playlists stays the ⋯ menu's `music` entry.
import type { NodeHeaderAction } from '../../../core/node/nodeHeader'
import { playlistLink } from '../../../music-frame/protocol'
import type { MusicService, Playlist } from './musicModel'
import { closeBar, playInBar, playlistInBar, samePlaylist } from './musicPlayback'
import { useMusicConsent } from './useMusicConsent'
import { musicReleased } from './musicRelease'

/** The header action id of [service]'s playlist. */
export const musicActionId = (service: MusicService) => `music-${service}`

type Translate = (key: string, values?: Record<string, unknown>) => string

/** The header's music buttons for [music] (the node's playlists, already opened), read reactively: call it inside the
 *  header's computed. */
export function musicHeaderActions(music: readonly Playlist[], t: Translate): NodeHeaderAction[] {
  if (!musicReleased()) return [] // TEMPORARY: released dark (ADR 0131 decision 8) - no button at all
  const consent = useMusicConsent()
  return music.map((p) => {
    const service = t(`memory.music.service.${p.service}`)
    const base = { id: musicActionId(p.service), shown: true }
    if (!consent.offered(p.service)) {
      return {
        ...base, label: t('memory.music.open', { service }), icon: 'external-link' as const,
        onClick: () => void window.open(playlistLink(p), '_blank', 'noopener,noreferrer'),
      }
    }
    // A toggle: one name, its state pressed while this playlist is the bar's.
    const inBar = samePlaylist(playlistInBar.value, p)
    return {
      ...base, label: t('memory.music.play', { service }), icon: inBar ? ('music' as const) : ('play' as const), pressed: inBar,
      onClick: () => (inBar ? closeBar() : playInBar(p)),
    }
  })
}
