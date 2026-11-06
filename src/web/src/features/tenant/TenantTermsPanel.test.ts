// @vitest-environment happy-dom
// uc.admin.tenant-terms (web) — the owner-gated Terms rubric, rebuilt on `shared/ui/LocaleMarkdownEditor`
// (cr-terms-editor-lists-the-languages-that-have-terms). The panel had no test at all before this; what it owes
// one for is not the editor's chrome (that is pinned next to the component) but the **data shape**: the panel
// holds a `LocaleMap`, so a language is present because the server carries it, and `''` is a language somebody
// started rather than one that is absent. The two flat `de`/`en` refs it used to hold could say neither.
//
// The second thing only this file can pin: the starter template ASKS, and the question it asks depends on
// whether there is already text to lose.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./tenantTerms', () => ({
  getTenantTerms: vi.fn(),
  updateTenantTerms: vi.fn(),
}))
vi.mock('../spaces/scopes', () => ({ listMyTenants: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))

import TenantTermsPanel from './TenantTermsPanel.vue'
import { getTenantTerms, updateTenantTerms } from './tenantTerms'
import { listMyTenants } from '../spaces/scopes'
import { TERMS_TEMPLATE } from './termsTemplate'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { confirmRequest, settleConfirm } from '../../shared/state/useConfirm'
import { realI18n, t } from '../../test/i18n'
import { myTenant } from '../../test/models'

const stubs = {
  VeyqButton: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button v-bind="$attrs" :disabled="loading || disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  VeyqMessage: { props: ['tone'], template: '<div class="msg"><slot /></div>' },
  VeyqDialog: { props: ['visible', 'header'], emits: ['update:visible'], template: '<div class="dlg"><h2>{{ header }}</h2><slot /></div>' },
  // A native select in the layer's place: Reka's list only renders while open, in a portal.
  VeyqSelect: { props: ['modelValue', 'options'], template: '<select class="sel"><option v-for="o in options" :key="o.value">{{ o.label }}</option></select>' },
}

async function mountPanel() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }],
  })
  const wrapper = mount(TenantTermsPanel, { global: { plugins: [router, realI18n()], stubs } })
  await flushPromises()
  return wrapper
}

const rows = (w: VueWrapper) => w.findAll('.locale-md-row')
const langs = (w: VueWrapper) => rows(w).map((r) => r.find('.locale-md-lang').text())
const button = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)
/** Open the editor's "Add language" and read the languages it offers. */
async function offered(w: VueWrapper): Promise<string[]> {
  await button(w, t('localeEditor.addLanguage'))!.trigger('click')
  return w.findAll('.sel option').map((o) => o.text())
}
/** The "Insert template" action inside a given language's row — the slot the panel hangs there. */
const templateButton = (w: VueWrapper, index: number) =>
  rows(w)[index].findAll('button').find((b) => b.text() === t('terms.insertTemplate'))
const saveButton = (w: VueWrapper) => button(w, t('terms.save'))!

/** Answer whatever the panel is currently asking, and let the click's promise chain settle. */
async function answer(w: VueWrapper, accepted: boolean) {
  expect(confirmRequest.value, 'nothing was asked').not.toBeNull()
  const asked = confirmRequest.value!
  settleConfirm(accepted)
  await flushPromises()
  await w.vm.$nextTick()
  return asked
}

const SECTION = { revision: 1, text: { de: '## Auf diesem Server', en: '## On this server' }, record: { de: '### Deine IP-Adresse', en: '### Your IP address' } }

describe('TenantTermsPanel — the languages that carry terms', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    if (confirmRequest.value) settleConfirm(false)
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't-1', role: 'OWNER' })])
  })

  it('lists only the languages that have terms — a language with none gets no box', async () => {
    // BITE 1 (lever: render `SUPPORTED_LOCALES` instead of the map's keys). This is the whole point of the CR:
    // before it, the missing language was an empty textarea that said nothing about whether that was deliberate.
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 2, accepted: true, operatorSection: SECTION, terms: { de: '# Bedingungen' } })
    const w = await mountPanel()

    expect(rows(w)).toHaveLength(1)
    expect(langs(w)).toEqual(['Deutsch'])
    expect(w.find('textarea').exists()).toBe(false) // no editor is open, so no box for anything
    // …and the one that is absent is offered as something to ADD, by name.
    expect(await offered(w)).toEqual(['English'])
  })

  it('treats a server null the same as an absent locale, and an empty string as a language that exists', async () => {
    // BITE 2 (lever: collapse the map with `filter(v => v)`). `LocalizedMarkdown` spells "not there" two ways;
    // only `''` means "somebody added this language and has not written it yet".
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 1, accepted: true, operatorSection: SECTION, terms: { de: '', en: null } })
    const w = await mountPanel()

    expect(langs(w)).toEqual(['Deutsch'])
    expect(await offered(w)).toEqual(['English'])
  })

  it('sends an absent language as absent, not as an empty string', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 1, accepted: true, operatorSection: SECTION, terms: { de: 'DE' } })
    vi.mocked(updateTenantTerms).mockResolvedValue({ termsRevision: 1, accepted: true, operatorSection: SECTION, terms: { de: 'DE' } })
    const w = await mountPanel()

    await saveButton(w).trigger('click')
    await flushPromises()
    const [, sent] = vi.mocked(updateTenantTerms).mock.calls[0]
    expect(sent).toEqual({ de: 'DE', en: undefined })
  })

  it('shows nothing but the empty state when the tenant has no terms at all', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 0, accepted: true, operatorSection: SECTION, terms: null })
    const w = await mountPanel()

    expect(rows(w)).toHaveLength(0)
    expect(w.text()).toContain(t('localeEditor.empty'))
    expect(await offered(w)).toEqual(['English', 'Deutsch'])
  })

  it('says out loud that a language with no text will not survive the save', async () => {
    // inbox #138's measured trap: the backend drops a blank locale on normalize, so the row the owner just
    // added vanishes on reload. The row is still the right intermediate state — what was missing is saying so.
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 1, accepted: true, operatorSection: SECTION, terms: { de: '', en: 'EN' } })
    const w = await mountPanel()
    expect(w.text()).toContain(t('terms.blankLocaleWarning', { langs: 'Deutsch' }))
  })

  it('stays quiet when every language it lists actually holds text', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 1, accepted: true, operatorSection: SECTION, terms: { de: 'DE', en: 'EN' } })
    const w = await mountPanel()
    expect(w.text()).not.toContain(t('terms.blankLocaleWarning', { langs: 'Deutsch' }))
  })

  it('names the {contact} placeholder literally in the Markdown hint', async () => {
    // The hint is a vue-i18n message with a `{contact}` slot: without the param the placeholder rendered as nothing
    // ("Use  as a placeholder…"), so the owner never learned the token to type.
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 0, accepted: true, operatorSection: SECTION, terms: { de: '' } })
    const w = await mountPanel()
    expect(w.text()).toContain('Use {contact} as a placeholder')
  })

  it('renders read-only for a member who is not the owner', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 2, accepted: true, operatorSection: SECTION, terms: { en: '# Terms' } })
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't-1', role: 'MEMBER' })])
    const w = await mountPanel()

    expect(w.text()).toContain(t('terms.readonlyNote'))
    expect(rows(w)).toHaveLength(0) // no editor at all, not a disabled one
    expect(button(w, t('terms.save'))).toBeUndefined()
  })
})

describe('TenantTermsPanel — the starter template asks', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    if (confirmRequest.value) settleConfirm(false)
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't-1', role: 'OWNER' })])
  })

  // BITE 3 (lever: restore the silent `if (!trim()) …` skip). Two cases, and they are not the same case: with
  // the skip back the EMPTY one still passes — the template still lands — while the overwrite one goes red.
  // That asymmetry is what says the dialog is about the overwrite and not merely about the insert.
  it('asks before filling an empty language, and fills it on yes', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 0, accepted: true, operatorSection: SECTION, terms: { de: '' } })
    vi.mocked(updateTenantTerms).mockResolvedValue({ termsRevision: 1, accepted: true, operatorSection: SECTION, terms: { de: TERMS_TEMPLATE.de } })
    const w = await mountPanel()

    await templateButton(w, 0)!.trigger('click')
    const asked = await answer(w, true)
    expect(asked.message).toBe(t('terms.insertTemplateAsk', { lang: 'Deutsch' }))

    await saveButton(w).trigger('click')
    await flushPromises()
    expect(vi.mocked(updateTenantTerms).mock.calls[0][1]).toEqual({ de: TERMS_TEMPLATE.de, en: undefined })
  })

  it('asks with the overwrite named when the language already holds text, and replaces it on yes', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 3, accepted: true, operatorSection: SECTION, terms: { de: 'Selbst geschrieben.' } })
    vi.mocked(updateTenantTerms).mockResolvedValue({ termsRevision: 4, accepted: true, operatorSection: SECTION, terms: { de: TERMS_TEMPLATE.de } })
    const w = await mountPanel()

    await templateButton(w, 0)!.trigger('click')
    const asked = await answer(w, true)
    expect(asked.message).toBe(t('terms.insertTemplateOverwriteAsk', { lang: 'Deutsch' }))

    await saveButton(w).trigger('click')
    await flushPromises()
    expect(vi.mocked(updateTenantTerms).mock.calls[0][1]).toEqual({ de: TERMS_TEMPLATE.de, en: undefined })
  })

  it('leaves the text alone when the overwrite is declined', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 3, accepted: true, operatorSection: SECTION, terms: { de: 'Selbst geschrieben.' } })
    vi.mocked(updateTenantTerms).mockResolvedValue({ termsRevision: 3, accepted: true, operatorSection: SECTION, terms: { de: 'Selbst geschrieben.' } })
    const w = await mountPanel()

    await templateButton(w, 0)!.trigger('click')
    await answer(w, false)

    await saveButton(w).trigger('click')
    await flushPromises()
    expect(vi.mocked(updateTenantTerms).mock.calls[0][1]).toEqual({ de: 'Selbst geschrieben.', en: undefined })
  })

  it('asks before removing a language, and a “no” keeps it', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 3, accepted: true, operatorSection: SECTION, terms: { de: 'DE', en: 'EN' } })
    const w = await mountPanel()

    const remove = w.findAll('button').find((b) => b.attributes('aria-label') === t('localeEditor.removeLang', { lang: 'Deutsch' }))
    await remove!.trigger('click')
    const asked = await answer(w, false)
    expect(asked.message).toBe(t('terms.removeLangAsk', { lang: 'Deutsch' }))
    // `SUPPORTED_LOCALES` order, which is where the panel's row order comes from — en before de.
    expect(langs(w)).toEqual(['English', 'Deutsch'])
  })

  it('removes the language once the question is answered yes', async () => {
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 3, accepted: true, operatorSection: SECTION, terms: { de: 'DE', en: 'EN' } })
    const w = await mountPanel()

    const remove = w.findAll('button').find((b) => b.attributes('aria-label') === t('localeEditor.removeLang', { lang: 'Deutsch' }))
    await remove!.trigger('click')
    await answer(w, true)
    expect(langs(w)).toEqual(['English'])
  })
})

describe('TenantTermsPanel — the operator section below the space’s terms (ADR 0035 decision 6)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    if (confirmRequest.value) settleConfirm(false)
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
  })

  it('shows it read-only to the owner, outside the editor, with nothing to edit or remove it by', async () => {
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't-1', role: 'OWNER' })])
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 2, accepted: true, operatorSection: SECTION, terms: { de: '# Bedingungen' } })
    const w = await mountPanel()
    const part = w.find('[data-testid="terms-operator-section"]')
    expect(part.text()).toContain(t('terms.operatorSectionNote'))
    expect(part.find('[data-testid="operator-section"]').text()).toContain('On this server')
    expect(part.find('textarea').exists()).toBe(false)
    expect(part.findAll('button')).toHaveLength(0)
    expect(rows(w)).toHaveLength(1) // control: the owner's own editor is there, holding only the space's language
  })

  it('shows it to a member of a space with no terms at all (revision 0: the section without a gate)', async () => {
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't-1', role: 'MEMBER' })])
    vi.mocked(getTenantTerms).mockResolvedValue({ termsRevision: 0, accepted: false, operatorSection: SECTION, terms: null })
    const w = await mountPanel()
    expect(w.text()).toContain(t('terms.noneSet'))
    expect(w.find('[data-testid="operator-section"]').exists()).toBe(true)
  })
})
