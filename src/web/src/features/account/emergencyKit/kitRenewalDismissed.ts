// Which kit layout's notice the member dismissed (ADR 0157 decision 5): a per-user, per-browser preference
// (`shared/state/scopedPreference.ts`) holding the `KIT_LAYOUT` the notice spoke of, nothing else. The notice of that
// layout never returns in this browser for this account; a later raise of `KIT_LAYOUT` — a new sheet with a sentence of
// its own (decision 2) — is told once more. Until the account id is known the value lives in memory only.
import { createScopedPreference } from '../../../shared/state/scopedPreference'

export const kitRenewalDismissed = createScopedPreference<string>(
  'veyq.kitRenewalDismissed',
  (raw) => (raw !== null && /^[0-9]{1,6}$/.test(raw) ? raw : undefined),
  '0',
)
