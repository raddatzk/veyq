// The Memories application's lifecycle hooks (ADR 0079; registered in `register.ts`). The shell calls them through
// the registry at the moments an application holds state it must keep in step with the platform: a scope is reset, a
// scope is entered, the Master Key rotates, the session ends (the music frame forgets the vendor's sign-in, ADR 0131).
import type { LifecycleHooks } from '../../core/applications/registry'
import { retagItemMarksUnderNewMasterKey } from './itemFavorites'
import { resetParticipantIndex } from './participantIndex'
import { forgetMusicSignIn } from './music/musicEnding'

export const memoriesLifecycle: LifecycleHooks = {
  // (The covers are core's since inbox #411 and the node pins since #409: `app/activeSpace` clears both and `rotateKeys` re-seals
  // the pins - Memories holds no per-scope state of its own to drop.)
  // The participants index (a person's profile) holds the Memories' content keys of the scopes it read: it dies with the
  // scope on screen, and so with a lock or a sign-out (`participantIndex.ts`).
  onScopeReset() {
    resetParticipantIndex()
  },

  // Item marks are tagged under the master key, so a rotation that skipped them would leave them unverifiable. Best-effort:
  // a mark whose tag the new key does not verify counts as someone else's set (ADR 0068 §9) and may not abort the rest of
  // the rotation. The member's node pins (ADR 0068) are core's and re-sealed by `rotateKeys` itself.
  async onMasterKeyRotated(oldMk, newMk) {
    try {
      await retagItemMarksUnderNewMasterKey(oldMk, newMk)
    } catch {
      /* see above */
    }
  },

  // A sign-out forgets the vendor's sign-in in the music frame, a lock does not (ADR 0131 decision 5, `music/musicEnding.ts`).
  onSessionEnding: forgetMusicSignIn,
}
