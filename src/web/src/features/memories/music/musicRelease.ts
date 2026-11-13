// TEMPORARY: the music release flag (musicReleased, ADR 0131 decision 8, owner 2026-11-13). Background music ships dark:
// until the deployment sets VEYQ_MUSIC_RELEASED=true the setup status says `musicReleased: false`, and every music
// surface of the web asks THIS helper and draws nothing - the header's buttons (`musicHeader.ts`), the ⋯ menu's entry
// (`MemoryDetail.vue`), the footer bar (`register.ts`) and the sign-out's ending step (`musicEnding.ts`). Nothing else
// reads the flag; with it on, the music behaves exactly as built (the `memories.music` entries decide).
//
// Removal at release: delete this file and each call of it (`git grep -iE 'music[._-]?released'` lists every site, the
// tests that assert the music is absent while off and the doc lines among them), `capabilityState.musicReleased` and
// the setup status's field (contracts/api/setup.openapi.yaml), and the backend's `veyq.music.released`.
import { capabilityState } from '../../../shared/state/capabilities'

/** Whether the deployment released background music (the setup status's `musicReleased`; false until it is read). */
export function musicReleased(): boolean {
  return capabilityState.musicReleased
}
