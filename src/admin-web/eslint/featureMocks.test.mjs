// The spellings rule S6 (`veyq-admin/feature-mocks-bare-fn`) must refuse, and the ones it must leave alone — above
// all the RELATIVE path whose meaning depends on the file it is written in (inbox #309). Which files the rule applies
// to is eslint.config.js' block (bitten live against the real config, not here).
import { RuleTester } from 'eslint'
import { describe, expect, it } from 'vitest'

import { plugin, reachesFeatures } from './featureMocks.js'

RuleTester.describe = describe
RuleTester.it = it
RuleTester.itOnly = it.only

const IN_FEATURES = '/repo/admin-web/src/features/tenants/CreateTenantDialog.test.ts'
const OUTSIDE = '/repo/admin-web/src/cli/cli.test.ts'
const bare = { messageId: 'bare' }
const tester = new RuleTester({ languageOptions: { ecmaVersion: 'latest', sourceType: 'module' } })

describe('reachesFeatures', () => {
  it('resolves a relative path against the test file', () => {
    expect(reachesFeatures('./tenants', IN_FEATURES)).toBe(true) // features/tenants/tenants
    expect(reachesFeatures('../instance/release', IN_FEATURES)).toBe(true) // features/instance/release
    expect(reachesFeatures('../../shared/inviteLink', IN_FEATURES)).toBe(false) // src/shared — not a features module
    expect(reachesFeatures('../features/tenants/tenants', OUTSIDE)).toBe(true)
    expect(reachesFeatures('./adminSession', OUTSIDE)).toBe(false) // src/cli
    expect(reachesFeatures('./TenantDetailPanel.vue', IN_FEATURES)).toBe(false) // a component, not a module
  })
})

tester.run('feature-mocks-bare-fn', plugin.rules['feature-mocks-bare-fn'], {
  valid: [
    { filename: IN_FEATURES, code: "vi.mock('./tenants', () => ({ createTenant: vi.fn(), tenantError: vi.fn() }))" },
    { filename: IN_FEATURES, code: "vi.mock('./tenants', async (orig) => ({ ...(await orig()), listTenants: vi.fn() }))" },
    { filename: IN_FEATURES, code: "vi.mock('./tenants', async (orig) => ({ GIB: (await orig()).GIB, listTenants: vi.fn() }))" },
    { filename: IN_FEATURES, code: "vi.mock('./tenants', () => ({ LIMIT: 3, Kind: Enum.A }))" },
    { filename: IN_FEATURES, code: "vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({}) }))" }, // a composable
    { filename: IN_FEATURES, code: "vi.mock('../../shared/inviteLink', () => ({ copyText: vi.fn(() => true) }))" }, // not features/
    { filename: OUTSIDE, code: "vi.mock('./adminSession', () => ({ signIn: vi.fn().mockResolvedValue(undefined) }))" }, // src/cli
    { filename: IN_FEATURES, code: "vi.mock('./Dialog.vue', () => ({ default: () => null }))" },
    { filename: IN_FEATURES, code: "vi.doMock('./tenants', () => ({ listTenants: () => 1 }))" }, // S6 is about vi.mock only
  ],
  invalid: [
    { filename: IN_FEATURES, code: "vi.mock('./tenants', () => ({ createTenant: vi.fn().mockResolvedValue({}) }))", errors: [bare] },
    { filename: IN_FEATURES, code: "vi.mock('../instance/release', () => ({ getInstanceRelease: () => null }))", errors: [bare] },
    { filename: IN_FEATURES, code: "vi.mock('./tenants', () => { return { listTenants: function () {} } })", errors: [bare] },
    { filename: OUTSIDE, code: "vi.mock('../features/tenants/tenants', () => ({ a: () => 1, b: vi.fn(), c: vi.fn(f) }))", errors: [bare, bare] },
  ],
})
