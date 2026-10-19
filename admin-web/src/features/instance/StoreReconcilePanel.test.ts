// @vitest-environment happy-dom
// uc.admin.configure-object-store A7 (admin-web) — Server → Object store, the bucket reconcile. Each case is one
// observable of the owner's three conditions:
//   - only counting until the bucket is confirmed exclusive, and the confirm dialog says what it arms, in numbers;
//   - the candidates are shown with their age and the date the first goes — that date only while deleting is armed;
//   - pause and resume are offered once confirmed, and the verdict follows the server's answer;
//   - every verdict and every failure reason has its own words (total maps), and a refusal is said, not swallowed.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./storeReconcile', async () => {
  const actual = await vi.importActual<typeof import('./storeReconcile')>('./storeReconcile')
  return {
    ...actual,
    getStoreReconcile: vi.fn(),
    confirmExclusiveBucket: vi.fn(),
    withdrawExclusiveBucket: vi.fn(),
    pauseReconcile: vi.fn(),
    resumeReconcile: vi.fn(),
  }
})
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import StoreReconcilePanel from './StoreReconcilePanel.vue'
import {
  confirmExclusiveBucket,
  getStoreReconcile,
  pauseReconcile,
  resumeReconcile,
  withdrawExclusiveBucket,
  StoreReconcileFailureReasonEnum,
  StoreReconcileVerdictEnum,
  type StoreReconcile,
} from './storeReconcile'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  Button: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}
const t = (k: string, p?: Record<string, unknown>) => i18n.global.t(k, p ?? {})

const FIRST_SEEN = new Date('2026-10-17T12:01:02Z')
const FIRST_DUE = new Date('2026-11-16T12:01:02Z')

function reconcile(over: Partial<StoreReconcile> = {}): StoreReconcile {
  return {
    verdict: StoreReconcileVerdictEnum.NotConfirmed,
    graceSeconds: 30 * 86_400,
    lastPass: {
      startedAt: new Date('2026-10-17T12:00:00Z'), completedAt: new Date('2026-10-17T12:04:31Z'), objects: 18263,
      orphanObjects: 23, orphanBytes: 61_865_984, notVeyqObjects: 1, enqueuedKeys: 0,
    },
    passInProgress: undefined,
    failure: undefined,
    candidates: {
      storageKeys: 9, objects: 23, bytes: 61_865_984, dueKeys: 2, oldestFirstSeenAt: FIRST_SEEN,
      newestFirstSeenAt: new Date('2026-10-17T12:03:40Z'), firstDueAt: FIRST_DUE,
    },
    exclusiveBucketConfirmedAt: undefined,
    pausedAt: undefined,
    clocksRestartedAt: undefined,
    clocksRestartReason: null,
    reclaim: { pending: 0, abandoned: 0 },
    ...over,
  }
}
const armed = (over: Partial<StoreReconcile> = {}) =>
  reconcile({ verdict: StoreReconcileVerdictEnum.Armed, exclusiveBucketConfirmedAt: new Date('2026-10-17T12:10:00Z'), ...over })

async function mountPanel(initial: StoreReconcile) {
  vi.mocked(getStoreReconcile).mockResolvedValue(initial)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const w = mount(StoreReconcilePanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return w
}
const at = (d: Date) => d.toLocaleString('en')

describe('StoreReconcilePanel (uc.admin.configure-object-store A7, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('only counts while the bucket is not confirmed, and says when the grace would end without promising a deletion', async () => {
    const w = await mountPanel(reconcile())
    expect(w.find('.reconcile-verdict').text()).toBe(t('reconcile.verdict.NOT_CONFIRMED'))
    expect(w.find('.reconcile-candidates').text()).toContain('9 uploads (23 objects, 59 MB)')
    expect(w.find('.reconcile-due').text()).toBe(t('reconcile.notDeleting', { at: at(FIRST_DUE) }))
    expect(w.find('.reconcile-pass').text()).toContain('18263 objects, 23 of them claimed by no record')
    expect(w.find('.reconcile-confirm').exists()).toBe(true)
    expect(w.find('.reconcile-pause').exists()).toBe(false)
  })

  it('asks before confirming, with the grace and the counts it arms, then shows the first deletion date', async () => {
    vi.mocked(confirmExclusiveBucket).mockResolvedValue(armed())
    const w = await mountPanel(reconcile())
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.reconcile-confirm').trigger('click')
    await flushPromises()
    expect(confirmAsk).toHaveBeenCalledWith(expect.objectContaining({ message: t('reconcile.confirmBody', { grace: t('reconcile.graceDays', { n: 30 }), keys: 9, due: 2 }) }))
    expect(confirmExclusiveBucket).toHaveBeenCalledOnce()
    expect(w.find('.reconcile-verdict').text()).toBe(t('reconcile.verdict.ARMED', { grace: t('reconcile.graceDays', { n: 30 }) }))
    expect(w.find('.reconcile-due').text()).toBe(t('reconcile.firstDue', { at: at(FIRST_DUE) }))
    expect(w.find('.reconcile-confirm').exists()).toBe(false)
  })

  it('confirms nothing when the dialog is dismissed — control on the same button', async () => {
    const w = await mountPanel(reconcile())
    confirmAsk.mockResolvedValueOnce(false)
    await w.find('.reconcile-confirm').trigger('click')
    await flushPromises()
    expect(confirmExclusiveBucket).not.toHaveBeenCalled()
  })

  it('pauses and resumes once confirmed, following the verdict the server answers', async () => {
    vi.mocked(pauseReconcile).mockResolvedValue(armed({ verdict: StoreReconcileVerdictEnum.Paused, pausedAt: new Date() }))
    vi.mocked(resumeReconcile).mockResolvedValue(armed())
    const w = await mountPanel(armed())
    await w.find('.reconcile-pause').trigger('click')
    await flushPromises()
    expect(pauseReconcile).toHaveBeenCalledOnce()
    expect(w.find('.reconcile-verdict').text()).toBe(t('reconcile.verdict.PAUSED'))
    expect(w.find('.reconcile-pause').exists()).toBe(false)
    await w.find('.reconcile-resume').trigger('click')
    await flushPromises()
    expect(resumeReconcile).toHaveBeenCalledOnce()
    expect(w.find('.reconcile-verdict').attributes('data-verdict')).toBe('ARMED')
  })

  it('withdraws the confirmation', async () => {
    vi.mocked(withdrawExclusiveBucket).mockResolvedValue(reconcile())
    const w = await mountPanel(armed())
    await w.find('.reconcile-withdraw').trigger('click')
    await flushPromises()
    expect(withdrawExclusiveBucket).toHaveBeenCalledOnce()
    expect(w.find('.reconcile-confirm').exists()).toBe(true)
  })

  it('says a refused confirmation in words', async () => {
    vi.mocked(confirmExclusiveBucket).mockRejectedValue(httpError(409, 'no_store'))
    const w = await mountPanel(reconcile())
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.reconcile-confirm').trigger('click')
    await flushPromises()
    expect(w.find('.reconcile-error').text()).toBe(t('reconcile.errors.noStore'))
  })

  it.each(Object.values(StoreReconcileFailureReasonEnum))('names the failure %s beside the last pass', async (reason) => {
    const failedAt = new Date('2026-10-17T13:00:00Z')
    const w = await mountPanel(reconcile({ failure: { at: failedAt, reason } }))
    expect(w.find('.reconcile-failure').text()).toBe(t(`reconcile.failure.${reason}`, { at: at(failedAt) }))
    expect(w.find('.reconcile-pass').exists()).toBe(true)
  })

  it.each(Object.values(StoreReconcileVerdictEnum))('has words for the verdict %s', async (verdict) => {
    const w = await mountPanel(reconcile({ verdict }))
    expect(w.find('.reconcile-verdict').text()).toBe(t(`reconcile.verdict.${verdict}`, { grace: t('reconcile.graceDays', { n: 30 }) }))
    expect(w.find('.reconcile-verdict').text()).not.toBe(`reconcile.verdict.${verdict}`)
  })

  // Found in the live proof (a three-minute test grace read "0 days"): below a day the grace is said in minutes.
  it('says a grace of less than a day in minutes, never as 0 days', async () => {
    const w = await mountPanel(armed({ graceSeconds: 180 }))
    expect(w.find('.reconcile-verdict').text()).toBe(t('reconcile.verdict.ARMED', { grace: t('reconcile.graceMinutes', { n: 3 }) }))
    expect(w.find('.reconcile-verdict').text()).not.toContain('0 days')
  })

  it('offers no switch while no store is in use', async () => {
    const w = await mountPanel(reconcile({ verdict: StoreReconcileVerdictEnum.NoStore, lastPass: undefined }))
    expect(w.find('button').exists()).toBe(false)
  })

  it('says why every grace period restarted', async () => {
    const restarted = new Date('2026-10-18T08:00:00Z')
    const w = await mountPanel(reconcile({ clocksRestartedAt: restarted, clocksRestartReason: 'GAP' }))
    expect(w.find('.reconcile-restart').text()).toBe(t('reconcile.restart.GAP', { at: at(restarted) }))
  })
})
