// @vitest-environment happy-dom
// uc.admin.learn-of-new-releases (admin-web) — what the daily release check found, under Server → Release. Each case
// is one observable the Server Owner depends on:
//   - an unscheduled major is marked urgent, apart from the other notices (main flow 2);
//   - a build with no release stamp says it checks nothing, rather than "nothing newer" (precondition);
//   - switching the check off stores it and the panel says it no longer looks (A1), from the server's answer;
//   - a source that is not an http(s) URL is refused in words;
//   - whether a find is also mailed is said beside the switch, from the instance mail's confirmation — and a mail state
//     that cannot be read costs that line only, never the notices.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./releaseNotices', async () => {
  const actual = await vi.importActual<typeof import('./releaseNotices')>('./releaseNotices')
  return { ...actual, getReleaseNotices: vi.fn(), setReleaseCheck: vi.fn() }
})

vi.mock('./instanceMail', async () => {
  const actual = await vi.importActual<typeof import('./instanceMail')>('./instanceMail')
  return { ...actual, getInstanceMail: vi.fn() }
})

import ReleaseNoticesPanel from './ReleaseNoticesPanel.vue'
import { getInstanceMail, type InstanceMailSettings } from './instanceMail'
import { getReleaseNotices, setReleaseCheck, type InstanceReleaseNotice, type InstanceReleaseNotices } from './releaseNotices'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-severity="tone"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqCheckbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" class="check" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
  VeyqButton: {
    props: ['label', 'disabled'],
    emits: ['click'],
    template: '<button type="button" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}

function notice(kind: InstanceReleaseNotice['kind'], release: string, date: string | null = null): InstanceReleaseNotice {
  return { id: `${kind}-${release}`, kind, release, date: date ? new Date(`${date}T00:00:00Z`) : undefined, daysAhead: undefined, foundAt: new Date(), mailedAt: undefined }
}

function read(over: Partial<InstanceReleaseNotices> = {}): InstanceReleaseNotices {
  return { releaseLine: true, enabled: true, source: undefined, lastSuccessAt: new Date('2026-10-16T04:00:00Z'), notices: [], ...over }
}

function mail(confirmation: InstanceMailSettings['confirmation']): InstanceMailSettings {
  return { tls: true, passwordSet: true, configSecretState: 'OK', address: 'owner@example.com', confirmation }
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(ReleaseNoticesPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('ReleaseNoticesPanel (uc.admin.learn-of-new-releases, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(getInstanceMail).mockResolvedValue(mail('NOT_CONFIGURED'))
  })

  it('marks an unscheduled major urgent, apart from a release of the running major', async () => {
    vi.mocked(getReleaseNotices).mockResolvedValue(read({
      notices: [notice('UNSCHEDULED_MAJOR', '2.0'), notice('RELEASE', '1.9'), notice('PLANNED_MAJOR', '3.0', '2029-05-01')],
    }))
    const w = await mountPanel()
    const urgent = w.findAll('.msg[data-severity="error"]')
    expect(urgent).toHaveLength(1)
    expect(urgent[0].text()).toContain(i18n.global.t('releaseCheck.urgent'))
    expect(urgent[0].text()).toContain('2.0')
    // Control on the same predicate: the other notices are listed, not urgent.
    const listed = w.findAll('.notice-list .notice').map((n) => n.text())
    expect(listed).toEqual([
      i18n.global.t('releaseCheck.kindRelease', { release: '1.9' }),
      i18n.global.t('releaseCheck.kindPlanned', { major: '3', release: '3.0', day: '2029-05-01' }),
    ])
  })

  it('says an unstamped build checks nothing, and does not claim nothing newer exists', async () => {
    vi.mocked(getReleaseNotices).mockResolvedValue(read({ releaseLine: false, lastSuccessAt: undefined }))
    const w = await mountPanel()
    expect(w.text()).toContain(i18n.global.t('releaseCheck.noReleaseLine'))
    expect(w.text()).not.toContain(i18n.global.t('releaseCheck.none'))
  })

  it('switches the check off, keeping the source, and says it no longer looks', async () => {
    vi.mocked(getReleaseNotices).mockResolvedValue(read({ source: 'https://mirror.example.com/veyq' }))
    vi.mocked(setReleaseCheck).mockImplementation(async (enabled, source) => read({ enabled, source: source ?? undefined }))
    const w = await mountPanel()
    expect(w.text()).not.toContain(i18n.global.t('releaseCheck.off'))
    await w.find('.check').setValue(false)
    await w.find('.check-save').trigger('click')
    await flushPromises()
    expect(setReleaseCheck).toHaveBeenCalledWith(false, 'https://mirror.example.com/veyq')
    expect(w.text()).toContain(i18n.global.t('releaseCheck.off'))
  })

  it('refuses a source that is not an http(s) URL in words', async () => {
    vi.mocked(getReleaseNotices).mockResolvedValue(read())
    vi.mocked(setReleaseCheck).mockRejectedValue(httpError(400, 'invalid_request'))
    const w = await mountPanel()
    await w.find('.check-source').setValue('ftp://mirror.example.com')
    await w.find('.check-save').trigger('click')
    await flushPromises()
    expect(setReleaseCheck).toHaveBeenCalledWith(true, 'ftp://mirror.example.com')
    expect(w.text()).toContain(i18n.global.t('releaseCheck.invalidSource'))
  })

  it('names the address a find is mailed to once instance mail is confirmed', async () => {
    vi.mocked(getReleaseNotices).mockResolvedValue(read())
    vi.mocked(getInstanceMail).mockResolvedValue(mail('CONFIRMED'))
    const w = await mountPanel()
    expect(w.find('.check-mail').text()).toBe(i18n.global.t('releaseCheck.mailed', { address: 'owner@example.com' }))
    expect(w.find('.check-mail-open').exists()).toBe(false)
  })

  it('says finds are only shown without confirmed mail, and leads to Server → Mail', async () => {
    vi.mocked(getReleaseNotices).mockResolvedValue(read())
    const w = await mountPanel()
    expect(w.find('.check-mail').text()).toContain(i18n.global.t('releaseCheck.mailNotSetUp'))
    await w.find('.check-mail-open').trigger('click')
    expect(w.emitted('openMail')).toHaveLength(1)
    // Set up but not confirmed is its own sentence: the fix is a code, not the settings.
    vi.mocked(getInstanceMail).mockResolvedValue(mail('CODE_SENT'))
    const w2 = await mountPanel()
    expect(w2.find('.check-mail').text()).toContain(i18n.global.t('releaseCheck.mailUnconfirmed'))
    expect(w2.text()).not.toContain(i18n.global.t('releaseCheck.mailed', { address: 'owner@example.com' }))
  })

  it('says nothing about mail while the check is off or has nothing to compare', async () => {
    vi.mocked(getInstanceMail).mockResolvedValue(mail('CONFIRMED'))
    vi.mocked(getReleaseNotices).mockResolvedValue(read({ enabled: false }))
    expect((await mountPanel()).find('.check-mail').exists()).toBe(false)
    vi.mocked(getReleaseNotices).mockResolvedValue(read({ releaseLine: false }))
    expect((await mountPanel()).find('.check-mail').exists()).toBe(false)
    // Control on the same predicate: on, with a release line, the line is there.
    vi.mocked(getReleaseNotices).mockResolvedValue(read())
    expect((await mountPanel()).find('.check-mail').exists()).toBe(true)
  })

  it('still shows the notices when the mail state cannot be read', async () => {
    vi.mocked(getReleaseNotices).mockResolvedValue(read({ notices: [notice('RELEASE', '1.9')] }))
    vi.mocked(getInstanceMail).mockRejectedValue(httpError(500, 'internal'))
    const w = await mountPanel()
    expect(w.findAll('.notice-list .notice')).toHaveLength(1)
    expect(w.text()).not.toContain(i18n.global.t('releaseCheck.loadFailed'))
    expect(w.find('.check-mail').exists()).toBe(false)
  })
})
