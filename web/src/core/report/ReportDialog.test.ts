// @vitest-environment happy-dom
// The report dialog (uc.moderation.report, ADR 0093 § 1): what DSA Art. 16(2) names is required before anything is sent
// — a category, a reason, the good-faith confirmation; an address is optional; the child-sexual-abuse category says no
// name is needed; above 100 items the dialog offers the enclosing node instead. And the action itself: shown only to
// whoever sees the node, and only while the instance publishes a moderation key.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import PrimeVue from 'primevue/config'
import Select from 'primevue/select'
import Checkbox from 'primevue/checkbox'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./report', async (orig) => ({ ...(await orig<typeof import('./report')>()), submitReport: vi.fn() }))

import ReportDialog from './ReportDialog.vue'
import { openReport, resetReporting, submitReport, type ReportTarget } from './report'
import { canReport, reportRowActions } from './reportActions'
import { ReportCategory } from '../../api/generated/moderation'
import { ShareRole } from '../../api/generated/sharing'
import type { Access } from '../access'
import { realI18n, t } from '../../test/i18n'
import { FOLDER_KIND } from '../node/kind'
import type { TreeNode } from '../node/tree'

const member = { mode: 'member', scopeId: 's', epochs: { current: 0, keys: new Map() } } as unknown as Access
const target = (i: number): ReportTarget => ({ kind: 'NODE', id: `5c09e000-0000-4000-8000-${String(i).padStart(12, '0')}`, keys: () => null })

function mountDialog() {
  return mount(ReportDialog, { attachTo: document.body, global: { plugins: [realI18n(), PrimeVue] } })
}
const send = () => document.body.querySelector<HTMLButtonElement>('.veyq-report-send')

beforeEach(() => {
  document.body.innerHTML = ''
  resetReporting()
  vi.mocked(submitReport).mockReset()
})

describe('ReportDialog', () => {
  it('sends nothing until a category, a reason and the good-faith tick are given; the address stays optional', async () => {
    vi.mocked(submitReport).mockResolvedValue({ reportId: '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f', receivedAt: new Date() })
    const w = mountDialog()
    openReport({ access: member, scopeId: 's', targets: [target(1)] })
    await flushPromises()
    expect(send()!.disabled).toBe(true)
    w.findComponent(Select).vm.$emit('update:modelValue', ReportCategory.IllegalContent)
    await flushPromises()
    expect(send()!.disabled, 'no reason').toBe(true)
    const reason = document.body.querySelector<HTMLTextAreaElement>('#veyq-report-reason')!
    reason.value = 'because'
    reason.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(send()!.disabled, 'no good-faith tick').toBe(true)
    w.findComponent(Checkbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    expect(send()!.disabled).toBe(false)
    send()!.click()
    await flushPromises()
    expect(submitReport).toHaveBeenCalledWith(expect.anything(), [expect.objectContaining({ kind: 'NODE' })], {
      category: ReportCategory.IllegalContent, reason: 'because', contact: '',
    })
    expect(document.body.querySelector('.veyq-report-receipt')!.textContent).toContain('0c1d2e3f')
    w.unmount()
  })

  it('links the privacy notice’s passage on the contact, beside the field that takes it (GDPR Art. 13)', async () => {
    const w = mountDialog()
    openReport({ access: member, scopeId: 's', targets: [target(1)] })
    await flushPromises()
    const link = document.body.querySelector<HTMLAnchorElement>('.veyq-report-privacy')!
    expect(link.getAttribute('href')).toBe('/privacy#reporter-contact')
    expect(link.getAttribute('target')).toBe('_blank') // a share page's key rides its fragment
    expect(link.textContent).toBe(t('privacyNotice.atReport'))
    w.unmount()
  })

  it('says a report of child sexual abuse material needs no name', async () => {
    const w = mountDialog()
    openReport({ access: member, scopeId: 's', targets: [target(1)] })
    await flushPromises()
    expect(document.body.querySelector('.veyq-report-csam')).toBeNull()
    w.findComponent(Select).vm.$emit('update:modelValue', ReportCategory.ChildSexualAbuse)
    await flushPromises()
    expect(document.body.querySelector('.veyq-report-csam')!.textContent).toBe(t('report.csamNote'))
    w.unmount()
  })

  it('above 100 items offers the enclosing node instead, and reports that one', async () => {
    const w = mountDialog()
    const enclosing = target(999)
    openReport({ access: member, scopeId: 's', targets: Array.from({ length: 101 }, (_, i) => target(i)), enclosing })
    await flushPromises()
    expect(document.body.textContent).toContain(t('report.tooMany', { count: 101 }))
    expect(document.body.querySelector('#veyq-report-reason'), 'no form above the cap').toBeNull()
    document.body.querySelector<HTMLButtonElement>('.veyq-report-enclosing')!.click()
    await flushPromises()
    expect(document.body.querySelector('#veyq-report-reason')).not.toBeNull()
    expect(document.body.textContent).toContain(t('report.items', { count: 1 }, 1))
    w.unmount()
  })
})

describe('the Report action', () => {
  const node = { id: 'n', kind: FOLDER_KIND } as TreeNode
  const guest = (role: ShareRole) => ({ mode: 'guest', role }) as unknown as Access

  it('is offered to a member with open keys and a viewing guest, never to an upload link\'s guest', () => {
    expect(canReport(member)).toBe(true)
    expect(canReport({ mode: 'member', epochs: null } as unknown as Access)).toBe(false)
    expect(canReport(guest(ShareRole.Read))).toBe(true)
    expect(canReport(guest(ShareRole.ReadWrite))).toBe(true)
    expect(canReport(guest(ShareRole.WriteOnly))).toBe(false)
  })

  it('is not shown while the instance publishes no moderation key (ADR 0099 § 3)', () => {
    expect(reportRowActions(t, false, member, node, () => {})).toEqual([])
    expect(reportRowActions(t, true, member, node, () => {}).map((a) => a.id)).toEqual(['report'])
  })
})
