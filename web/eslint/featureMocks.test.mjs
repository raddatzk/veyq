// The spellings rule S6 (`veyq-web/feature-mocks-bare-fn`) must refuse, and the ones it must leave alone — above all
// the RELATIVE path whose meaning depends on the file it is written in (inbox #286). Which files the rule applies to
// is eslint.config.js' block (bitten live against the real config, not here).
import { RuleTester } from 'eslint'
import { describe, expect, it } from 'vitest'

import { plugin, reachesFeatures } from './featureMocks.js'

RuleTester.describe = describe
RuleTester.it = it
RuleTester.itOnly = it.only

const IN_FEATURES = '/repo/web/src/features/memories/components/Thing.test.ts'
const OUTSIDE = '/repo/web/src/components/app/AppSidebar.test.ts'
const bare = { messageId: 'bare' }
const tester = new RuleTester({ languageOptions: { ecmaVersion: 'latest', sourceType: 'module' } })

describe('reachesFeatures', () => {
  it('resolves a relative path against the test file', () => {
    expect(reachesFeatures('../media', IN_FEATURES)).toBe(true) // features/memories/media
    expect(reachesFeatures('./shareLink', IN_FEATURES)).toBe(true)
    expect(reachesFeatures('../../spaces/scopes', IN_FEATURES)).toBe(true) // features/spaces/scopes
    expect(reachesFeatures('../../../app/vault', IN_FEATURES)).toBe(false) // src/app — not a features module
    expect(reachesFeatures('../../features/memories/coverStore', OUTSIDE)).toBe(true)
    expect(reachesFeatures('../account/identityPins', OUTSIDE)).toBe(false) // src/components/account
    expect(reachesFeatures('./ShareDialog.vue', IN_FEATURES)).toBe(false) // a component, not a module
  })
})

tester.run('feature-mocks-bare-fn', plugin.rules['feature-mocks-bare-fn'], {
  valid: [
    { filename: IN_FEATURES, code: "vi.mock('../media', () => ({ openBlob: vi.fn(), uploadCoverBlob: vi.fn() }))" },
    { filename: IN_FEATURES, code: "vi.mock('../media', async (orig) => ({ ...(await orig()), openBlob: vi.fn() }))" },
    { filename: IN_FEATURES, code: "vi.mock('../media', () => ({ FULL_FRAME: { x: 0 }, LIMIT: 3, Kind: Enum.A }))" },
    { filename: IN_FEATURES, code: "vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({}) }))" }, // a composable
    { filename: IN_FEATURES, code: "vi.mock('../../../app/vault', () => ({ unlockOrigin: () => 'cold' }))" }, // not features/
    { filename: OUTSIDE, code: "vi.mock('../account/identityPins', () => ({ getPin: vi.fn(() => 1) }))" }, // src/components/account
    { filename: IN_FEATURES, code: "vi.mock('./Dialog.vue', () => ({ default: () => null }))" },
    { filename: IN_FEATURES, code: "vi.doMock('../media', () => ({ openBlob: () => 1 }))" }, // S6 is about vi.mock only
  ],
  invalid: [
    { filename: IN_FEATURES, code: "vi.mock('../media', () => ({ hasDerivatives: vi.fn(() => true) }))", errors: [bare] },
    { filename: IN_FEATURES, code: "vi.mock('./tree', () => ({ nodeContentKey: () => new Uint8Array(32) }))", errors: [bare] },
    { filename: IN_FEATURES, code: "vi.mock('../../spaces/scopes', () => ({ list: vi.fn().mockResolvedValue([]) }))", errors: [bare] },
    { filename: IN_FEATURES, code: "vi.mock('../media', () => { return { openBlob: function () {} } })", errors: [bare] },
    { filename: OUTSIDE, code: "vi.mock('../../features/memories/coverStore', () => ({ ensureCovers: vi.fn(() => 0) }))", errors: [bare] },
    { filename: OUTSIDE, code: "vi.mock('../../features/x', () => ({ a: () => 1, b: vi.fn(), c: vi.fn(f) }))", errors: [bare, bare] },
  ],
})
