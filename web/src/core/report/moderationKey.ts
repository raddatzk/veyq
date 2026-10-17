// The one read of the published moderation key (`getModerationKey`, unauthenticated): a module of its own because every
// level, header and share page asks it when it mounts (`useReportOffered`), so a unit test that mounts one would reach
// the network. `src/test/setup.ts` stands in for this module in every unit test — no epoch, no report action — and a
// test of reporting itself takes it back (`vi.unmock`).
import { publicModerationApi } from '../../api/client'
import type { ModerationEpochKey } from '../../api/generated/moderation'

export const readModerationKey = (): Promise<ModerationEpochKey> => publicModerationApi().getModerationKey()
