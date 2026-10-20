// Accepting a contact's changed identity key (ADR 0028 Decision 2) — the one entry both accept surfaces use (the
// review dialog and the group add's inline notice). It lives here, not in `identityPins`, so the pin store stays
// free of the tasks feature (whose rotation worker already depends on this one's scopes).
import { acceptKeyChange } from '../account/identityPins'
import { runScopeRotationWorker } from '../tasks/scopeRotationWorker'

/**
 * Run one pass of the scope-rotation worker, then accept the contact's changed key (uc.account.rotate-keys E9).
 *
 * **Why the pass comes first.** A grant is verified under the key its granter signed it with, and it opens only while
 * this account's pin still holds that key (ADR 0028 § What is still open). When the contact rotated their account
 * keys, every grant they signed for us is signed by the key we are about to replace; once the pin moves, those grants
 * are refused (`granter_key_changed`) until a covering rotation replaces them. If we are an owner of such a scope, we
 * are the one who can perform that rotation — and only while the grant still opens. So we drain first.
 *
 * **The pass's outcome is ignored, and its failure never blocks the accept.** The worker already runs on every app
 * entry and every 45 s, so this only closes the remaining window. What it could not cover (a job leased to someone
 * else, a scope we do not own) stays refused for us until another owner completes it — the retired key is never
 * trusted again, which is the point of accepting (ADR 0028 Decision 2).
 */
export async function acceptChangedKey(userId: string, identityPublicKeyB64: string): Promise<void> {
  try {
    await runScopeRotationWorker()
  } catch {
    // Deliberately swallowed: the accept is the human's decision, and the drain only improves its timing.
  }
  await acceptKeyChange(userId, identityPublicKeyB64)
}
