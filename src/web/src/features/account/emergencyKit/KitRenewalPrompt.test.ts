// @vitest-environment happy-dom
// The one-time "a clearer Emergency Kit exists" notice (ADR 0157 decision 5, uc.account.renew-emergency-kit step 2, E2):
// shown while the account's last kit is older than this client's layout (or it has no record), with the layout's sentence
// and that the kit the person has keeps working; dismissed per user and browser and then never again for that layout,
// not even after a reload. Whether the shell mounts it beside another notice is AppShell.unconfirmedEmail.test.ts's.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import type { AccountKitRecordApi } from '../../../api/generated/account'

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../../../api/client', () => ({ accountKitRecordApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))

import KitRenewalPrompt from './KitRenewalPrompt.vue'
import { accountKitRecordApi } from '../../../api/client'
import { fakeApi } from '../../../test/apiMock'
import { account } from '../../../app/account'
import { clearVault, setUnlocked } from '../../../app/vault'
import { realI18n, t } from '../../../test/i18n'
import { rememberCurrentKit, resetKitLayout, sealKitRecord } from './kitRecord'
import { KIT_LAYOUT } from './emergencyKit'

const MK = v1.randomBytes(32)
const KEY = 'veyq.kitRenewalDismissed'
const OLDER = b64std(v1.seal({ purpose: 'kit-record' }, MK, new TextEncoder().encode('{"layout":1}')))
let user = 0

function serve(blob: string | undefined) {
  return fakeApi(accountKitRecordApi, {
    getKitRecord: vi.fn<AccountKitRecordApi['getKitRecord']>().mockResolvedValue(blob === undefined ? {} : { blob }),
  })
}

async function mountPrompt() {
  resetKitLayout() // a reload: this unlock's read starts over (the dismissal lives in localStorage)
  const w = mount(KitRenewalPrompt, { global: { plugins: [realI18n()] } })
  await flushPromises()
  return w
}
const notice = (w: Awaited<ReturnType<typeof mountPrompt>>) => w.find('[data-testid="kit-renewal"]')

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  account.userId = `kit-user-${++user}` // a fresh owner per test: the dismissal is kept per user
  setUnlocked(MK.slice(), { kemSeed: new Uint8Array(32), sign: new Uint8Array(32) }, 'cold')
  serve(OLDER)
})
afterEach(() => {
  clearVault()
  resetKitLayout()
})

describe('KitRenewalPrompt', () => {
  it('an older record: says a clearer kit exists, what it does better, and that the kit the person has keeps working', async () => {
    const w = await mountPrompt()
    expect(notice(w).exists()).toBe(true)
    expect(w.text()).toContain(t('kit.notice.title'))
    expect(w.text()).toContain(t(`kit.layoutNews.${KIT_LAYOUT}`))
    expect(w.text()).toContain(t('kit.status.keepsWorking'))
  })

  it('no record (a kit made before the record existed) is an older one too', async () => {
    serve(undefined)
    expect(notice(await mountPrompt()).exists()).toBe(true)
  })

  it('a current record shows nothing', async () => {
    serve(sealKitRecord(MK))
    const w = await mountPrompt()
    expect(accountKitRecordApi).toHaveBeenCalled() // it did read: the positive control
    expect(notice(w).exists()).toBe(false)
  })

  it('a locked vault reads nothing and shows nothing', async () => {
    clearVault()
    const w = await mountPrompt()
    expect(accountKitRecordApi).not.toHaveBeenCalled()
    expect(notice(w).exists()).toBe(false)
  })

  it('"Not now" dismisses it for this user: the layout it spoke of under the user\'s own key, and a reload reads nothing and shows nothing', async () => {
    const w = await mountPrompt()
    await w.get('[data-testid="kit-renewal-dismiss"]').trigger('click')
    expect(notice(w).exists()).toBe(false)
    expect(localStorage.getItem(`${KEY}:u:${account.userId}`)).toBe(String(KIT_LAYOUT))

    vi.mocked(accountKitRecordApi).mockClear()
    const again = await mountPrompt()
    expect(notice(again).exists()).toBe(false)
    expect(accountKitRecordApi).not.toHaveBeenCalled() // decided from the stored dismissal alone
  })

  it('following the link opens Settings → Security and counts as dismissed', async () => {
    const w = await mountPrompt()
    await w.get('[data-testid="kit-renewal-open"]').trigger('click')
    expect(push).toHaveBeenCalledWith('/settings/security')
    expect(localStorage.getItem(`${KEY}:u:${account.userId}`)).toBe(String(KIT_LAYOUT))
    expect(notice(w).exists()).toBe(false)
  })

  it('one user\'s dismissal does not silence another account on the same browser', async () => {
    const w = await mountPrompt()
    await w.get('[data-testid="kit-renewal-dismiss"]').trigger('click')
    account.userId = `kit-user-${++user}`
    expect(notice(await mountPrompt()).exists()).toBe(true)
  })

  it('a dismissal of an earlier layout\'s notice does not silence a later raise (ADR 0157 decision 2)', async () => {
    localStorage.setItem(`${KEY}:u:${account.userId}`, String(KIT_LAYOUT - 1))
    expect(notice(await mountPrompt()).exists()).toBe(true)
  })

  it('goes away on its own once a kit of this layout was made (the record is current)', async () => {
    const w = await mountPrompt()
    expect(notice(w).exists()).toBe(true)
    rememberCurrentKit()
    await flushPromises()
    expect(notice(w).exists()).toBe(false)
  })
})
