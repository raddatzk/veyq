// The one read of the published moderation key (`getModerationKey`, unauthenticated): a module of its own because every
// level, header and share page asks it when it mounts (`useReportOffered`), so a unit test that mounts one would reach
// the network. `src/test/setup.ts` stands in for this module in every unit test — no epoch, no report action — and a
// test of reporting itself takes it back (`vi.unmock`).
import { publicModerationApi } from '../../api/client'
import type { ModerationEpochKey } from '../../api/generated/moderation'

/** The key published by the instance at `apiBase` (`''`: this one; another's for a guest of its link shown in a bookmark's
 *  page, whose report goes there — ADR 0121 decision 8). */
export const readModerationKey = (apiBase = ''): Promise<ModerationEpochKey> => publicModerationApi(apiBase).getModerationKey()
