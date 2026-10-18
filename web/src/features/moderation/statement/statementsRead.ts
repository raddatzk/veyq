// The one read of the caller's statements of reasons (`listMyStatements`): a module of its own because every blocked
// placeholder asks it when it mounts (`StatementNotice`), so a unit test that mounts one would reach the network.
// `src/test/setup.ts` stands in for this module in every unit test — no statement — and a test of the notice itself takes
// it back (`vi.unmock`), as `core/report/moderationKey.ts` is.
import { statementsApi } from '../../../api/client'
import type { ModerationStatement } from '../../../api/generated/moderation'
import { withRefresh } from '../../../app/sessionRefresh'

export const readMyStatements = async (): Promise<ModerationStatement[]> =>
  (await withRefresh(() => statementsApi().listMyStatements())).statements
