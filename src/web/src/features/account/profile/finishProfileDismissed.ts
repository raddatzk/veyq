// Whether the member has dismissed the one-time "finish your profile" prompt (ADR 0084 decision 7). A per-user, per-browser
// preference (`shared/state/scopedPreference.ts`): the flag `'1'` under the account's own key, nothing else - never an image,
// never a name. Until the account id is known the value lives in memory only (nothing is written under a key that names no owner).
import { createScopedPreference } from '../../../shared/state/scopedPreference'

export const finishProfileDismissed = createScopedPreference<'0' | '1'>(
  'veyq.finishProfileDismissed',
  (raw) => (raw === '1' ? '1' : raw === '0' ? '0' : undefined),
  '0',
)
