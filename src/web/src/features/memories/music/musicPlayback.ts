// What the music bar plays (uc.memory.music, ADR 0131 decision 5): the one playlist a viewer started from a Memory's or a
// chapter's header, held here for the bar in the shell's footer (`MusicBar.vue`, core's footer-bar slot), which outlives
// the page it was started on. A header asks `playInBar`; the bar reads `playlistInBar` and ends with `closeBar` - its
// Close, or its own unmount, which the shell does on a sign-out, a lock and when the share page is left. Another Memory's
// playlist replaces this one only when the viewer starts it: opening that Memory changes nothing here.
//
// What this holds is a playlist reference - content of the node it came from, opened under that node's key and already
// handed to the vendor's frame - never a key, never the node. It outlives a change of space on purpose (the music plays
// on), and no longer than the bar.
import { readonly, ref } from 'vue'
import type { Playlist } from './musicModel'

const inBar = ref<Playlist | null>(null)

/** Start [p] in the bar (it asks for the viewer's opt-in there where this browser has not given it). */
export function playInBar(p: Playlist): void {
  inBar.value = { ...p }
}

/** End what the bar plays: the bar removes its frame. */
export function closeBar(): void {
  inBar.value = null
}

/** Whether [a] and [b] are the same playlist of the same service. */
export function samePlaylist(a: Playlist | null, b: Playlist | null): boolean {
  return a !== null && b !== null && a.service === b.service && a.id === b.id
}

/** The playlist in the bar, read-only; null while it plays none. */
export const playlistInBar = readonly(inBar)
