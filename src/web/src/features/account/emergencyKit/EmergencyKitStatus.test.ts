// @vitest-environment happy-dom
// Settings → Security says which Emergency Kit the account has (uc.account.renew-emergency-kit step 2, ADR 0157
// decision 5): an older record and an absent one ("a clearer kit exists", the newest layout's sentence, that the kit the
// person has keeps working, and Make a new kit), a current one ("current", nothing offered), and a read that failed
// (said quietly, nothing offered). The renewal itself is KitRenewalDialog.test.ts's.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import type { AccountKitRecordApi } from '../../../api/generated/account'

vi.mock('../../../api/client', () => ({ accountKitRecordApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))

import EmergencyKitStatus from './EmergencyKitStatus.vue'
import { accountKitRecordApi } from '../../../api/client'
import { fakeApi } from '../../../test/apiMock'
import { clearVault, setUnlocked } from '../../../app/vault'
import { realI18n, t } from '../../../test/i18n'
import { resetKitLayout, sealKitRecord } from './kitRecord'
import { KIT_LAYOUT } from './emergencyKit'

const MK = v1.randomBytes(32)
const OLDER = b64std(v1.seal({ purpose: 'kit-record' }, MK, new TextEncoder().encode('{"layout":1}')))
const stubs = {
  VeyqButton: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  KitRenewalDialog: { props: ['visible'], template: '<div v-if="visible" class="renewal" />' },
}

function serve(answer: string | undefined | Error) {
  const getKitRecord = vi.fn<AccountKitRecordApi['getKitRecord']>()
  if (answer instanceof Error) getKitRecord.mockRejectedValue(answer)
  else getKitRecord.mockResolvedValue(answer === undefined ? {} : { blob: answer })
  return fakeApi(accountKitRecordApi, { getKitRecord })
}

async function mountStatus() {
  const w = mount(EmergencyKitStatus, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
  resetKitLayout()
  setUnlocked(MK.slice(), { kemSeed: new Uint8Array(32), sign: new Uint8Array(32) }, 'cold')
})
afterEach(() => {
  clearVault()
  resetKitLayout()
})

describe('EmergencyKitStatus', () => {
  it.each([
    ['an older record', OLDER],
    ['no record (a kit made before the record existed)', undefined],
  ])('%s: a clearer kit exists, what it does better, that the old kit keeps working, and Make a new kit', async (_name, blob) => {
    serve(blob)
    const w = await mountStatus()
    expect(w.find('[data-testid="kit-status-older"]').text()).toContain(t('kit.status.older'))
    expect(w.text()).toContain(t(`kit.layoutNews.${KIT_LAYOUT}`))
    expect(w.text()).toContain(t('kit.status.keepsWorking'))
    expect(w.find('[data-testid="kit-status-current"]').exists()).toBe(false)
    expect(w.find('.renewal').exists()).toBe(false)
    await w.get('[data-testid="kit-status-make"]').trigger('click')
    expect(w.find('.renewal').exists()).toBe(true)
  })

  it('a current record: says so, and offers nothing', async () => {
    serve(sealKitRecord(MK))
    const w = await mountStatus()
    expect(w.find('[data-testid="kit-status-current"]').text()).toBe(t('kit.status.current'))
    expect(w.find('[data-testid="kit-status-older"]').exists()).toBe(false)
    expect(w.find('[data-testid="kit-status-make"]').exists()).toBe(false)
  })

  it('a record that does not open reads as no record — an offer, never an error (E4)', async () => {
    serve(sealKitRecord(v1.randomBytes(32)))
    const w = await mountStatus()
    expect(w.find('[data-testid="kit-status-older"]').exists()).toBe(true)
    expect(w.find('[data-testid="kit-status-unreadable"]').exists()).toBe(false)
  })

  it('a read that failed says so quietly and offers nothing', async () => {
    serve(new Error('offline'))
    const w = await mountStatus()
    expect(w.find('[data-testid="kit-status-unreadable"]').text()).toBe(t('kit.status.unreadable'))
    expect(w.find('[data-testid="kit-status-make"]').exists()).toBe(false)
    expect(w.find('[data-testid="kit-status-current"]').exists()).toBe(false)
  })
})
