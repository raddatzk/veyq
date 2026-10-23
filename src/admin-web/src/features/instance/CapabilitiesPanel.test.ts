// @vitest-environment happy-dom
// ADR 0088 decision 12 — the Server → Capabilities page (uc.admin.switch-capabilities). Each case is one observable
// the owner depends on:
//   - the register reads as a tree, each entry in one of three states, in the console's locale;
//   - "off through" names the NEAREST ancestor that is itself off — the switch that brings the entry back;
//   - switching off asks first and shows the entry's own effect-when-off in that locale; cancel switches nothing;
//   - switching on asks nothing;
//   - a row re-renders from the PUT's answer, and descendants from a fresh read (their `effective` is the server's);
//   - a refused write is said in words, naming the entry.
// The entry texts below are invented fixtures, not the catalog's: the panel must show what the server serves.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./capabilities', async () => {
  const actual = await vi.importActual<typeof import('./capabilities')>('./capabilities')
  return {
    ...actual, listInstanceCapabilities: vi.fn(), setInstanceCapability: vi.fn(),
    setInstanceCapabilityRequirement: vi.fn(), clearInstanceCapabilityRequirement: vi.fn(),
  }
})
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import CapabilitiesPanel from './CapabilitiesPanel.vue'
import {
  clearInstanceCapabilityRequirement, listInstanceCapabilities, setInstanceCapability, setInstanceCapabilityRequirement,
} from './capabilities'
import type { InstanceCapability } from './capabilities'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-severity="tone"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqButton: {
    props: ['label', 'disabled'],
    emits: ['click'],
    template: '<button type="button" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}

function entry(id: string, name: string, off: boolean, effective: boolean): InstanceCapability {
  return {
    id,
    title: { en: `${name} EN`, de: `${name} DE` },
    purpose: { en: `What ${name} is for.`, de: `Wofür ${name} da ist.` },
    whenOff: { en: `Without ${name}, new ones are refused.`, de: `Ohne ${name} wird Neues abgelehnt.` },
    off,
    effective,
    tenantSwitchable: false,
    requirements: [],
  }
}

// One register with every shape the tree can take: a root-level entry on; a parent off; a child off on its own
// switch as well; a grandchild whose nearest off ancestor is that child, not the grandparent; a second child off
// only through the parent; and a leaf switched off under the switched-off parent.
const register = (): InstanceCapability[] => [
  entry('core.x', 'Ex', false, true),
  entry('core.a', 'Alpha', true, false),
  entry('core.a.b', 'Bravo', true, false),
  entry('core.a.b.c', 'Charlie', false, false),
  entry('core.a.d', 'Delta', false, false),
  entry('core.a.e', 'Echo', true, false),
]

async function mountPanel(initial: InstanceCapability[] = register()) {
  vi.mocked(listInstanceCapabilities).mockResolvedValue(initial)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(CapabilitiesPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}
type Panel = Awaited<ReturnType<typeof mountPanel>>
const t = (k: string, p?: Record<string, unknown>) => i18n.global.t(k, p ?? {})
const row = (w: Panel, id: string) => w.find(`[data-id="${id}"]`)
const stateOf = (w: Panel, id: string) => row(w, id).find('.capability__state').text()
const toggle = async (w: Panel, id: string) => {
  await row(w, id).find('.capability__toggle').trigger('click')
  await flushPromises()
}

describe('CapabilitiesPanel (ADR 0088, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('sets a requirement after showing what it does to what exists, refuses days out of range, and clears without asking', async () => {
    const withReq = (value: number | null) => ({
      ...entry('core.x', 'Ex', false, true),
      requirements: [{ name: 'linkMaxDays', kind: 'days' as const, value, title: { en: 'Links end', de: 'Links enden' }, whenSet: { en: 'Old links stop.', de: 'Alte Links stoppen.' } }],
    }) as InstanceCapability
    vi.mocked(setInstanceCapabilityRequirement).mockResolvedValue(withReq(7))
    vi.mocked(clearInstanceCapabilityRequirement).mockResolvedValue(withReq(null))
    const w = await mountPanel([withReq(null)])
    const req = () => w.find('[data-requirement="linkMaxDays"]')
    expect(req().text()).toContain(t('capabilities.requirementNone'))

    await req().find('input').setValue('0')
    await req().find('.requirement__set').trigger('click')
    await flushPromises()
    expect(setInstanceCapabilityRequirement).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('capabilities.requirementInvalidDays'))

    await req().find('input').setValue('7')
    confirmAsk.mockResolvedValueOnce(true)
    await req().find('.requirement__set').trigger('click')
    await flushPromises()
    expect(confirmAsk).toHaveBeenCalledWith(expect.objectContaining({ message: 'Old links stop.' }))
    expect(setInstanceCapabilityRequirement).toHaveBeenCalledWith('core.x', 'linkMaxDays', 7)
    expect(req().text()).toContain(t('capabilities.requirementDays', { days: 7 }))

    confirmAsk.mockClear()
    await req().find('.requirement__clear').trigger('click')
    await flushPromises()
    expect(confirmAsk).not.toHaveBeenCalled()
    expect(clearInstanceCapabilityRequirement).toHaveBeenCalledWith('core.x', 'linkMaxDays')
  })

  it('says which entries a tenant owner can narrow further, and only those', async () => {
    const w = await mountPanel([{ ...entry('core.x', 'Ex', false, true), tenantSwitchable: true }, entry('core.y', 'Why', false, true)])
    expect(row(w, 'core.x').find('.capability__tenant').text()).toBe(t('capabilities.tenantSwitchable'))
    expect(row(w, 'core.y').find('.capability__tenant').exists()).toBe(false)
  })

  it('shows the register as a tree, in served order, with each entry’s title, purpose and state', async () => {
    const w = await mountPanel()
    expect(w.findAll('.capability').map((li) => li.attributes('data-id'))).toEqual([
      'core.x', 'core.a', 'core.a.b', 'core.a.b.c', 'core.a.d', 'core.a.e',
    ])
    // the depth is the number of ancestors that are entries: `core` itself is not one
    expect(w.findAll('.capability').map((li) => li.attributes('style'))).toEqual(
      [0, 0, 1, 2, 1, 1].map((d) => `--capability-depth: ${d};`),
    )
    expect(row(w, 'core.x').find('.capability__title').text()).toBe('Ex EN')
    expect(row(w, 'core.x').find('.capability__purpose').text()).toBe('What Ex is for.')
    expect(stateOf(w, 'core.x')).toBe(t('capabilities.on'))
    expect(stateOf(w, 'core.a')).toBe(t('capabilities.off'))
    expect(stateOf(w, 'core.a.b')).toBe(t('capabilities.off')) // its own switch, though its parent is off too
    expect(stateOf(w, 'core.a.d')).toBe(t('capabilities.offThrough', { parent: 'Alpha EN' }))
    // the button follows the entry's OWN switch: an entry off only through its parent can still be switched off
    expect(row(w, 'core.a.d').find('.capability__toggle').text()).toBe(t('capabilities.switchOff'))
    expect(row(w, 'core.a').find('.capability__toggle').text()).toBe(t('capabilities.switchOn'))
  })

  it('names the NEAREST ancestor that is itself off, not the root of the branch', async () => {
    const w = await mountPanel()
    expect(stateOf(w, 'core.a.b.c')).toBe(t('capabilities.offThrough', { parent: 'Bravo EN' }))
    expect(stateOf(w, 'core.a.b.c')).not.toContain('Alpha')
  })

  it('shows the served words in German when the console speaks German', async () => {
    i18n.global.locale.value = 'de'
    const w = await mountPanel()
    expect(row(w, 'core.x').find('.capability__title').text()).toBe('Ex DE')
    expect(row(w, 'core.x').find('.capability__purpose').text()).toBe('Wofür Ex da ist.')
    expect(stateOf(w, 'core.a.b.c')).toBe('Aus, weil „Bravo DE“ aus ist')
  })

  it('asks before switching off and shows the entry’s own effect-when-off; cancel switches nothing', async () => {
    confirmAsk.mockResolvedValueOnce(false)
    const w = await mountPanel()
    await toggle(w, 'core.x')
    expect(confirmAsk).toHaveBeenCalledOnce()
    expect(confirmAsk.mock.calls[0][0]).toMatchObject({
      header: t('capabilities.confirmTitle', { title: 'Ex EN' }),
      message: 'Without Ex, new ones are refused.',
      acceptLabel: t('capabilities.switchOff'),
    })
    expect(setInstanceCapability).not.toHaveBeenCalled()
    expect(stateOf(w, 'core.x')).toBe(t('capabilities.on'))
  })

  it('shows the effect-when-off in German in a German console', async () => {
    i18n.global.locale.value = 'de'
    confirmAsk.mockResolvedValueOnce(false)
    const w = await mountPanel()
    await toggle(w, 'core.x')
    expect(confirmAsk.mock.calls[0][0]).toMatchObject({ header: '„Ex DE“ abschalten?', message: 'Ohne Ex wird Neues abgelehnt.' })
  })

  it('switches off on a yes and re-renders the row from the PUT’s answer', async () => {
    confirmAsk.mockResolvedValueOnce(true)
    vi.mocked(setInstanceCapability).mockResolvedValueOnce(entry('core.x', 'Ex', true, false))
    const w = await mountPanel()
    await toggle(w, 'core.x')
    expect(setInstanceCapability).toHaveBeenCalledWith('core.x', true)
    expect(stateOf(w, 'core.x')).toBe(t('capabilities.off'))
    expect(row(w, 'core.x').find('.capability__toggle').text()).toBe(t('capabilities.switchOn'))
    // a leaf: nothing below it changed, so no second read
    expect(listInstanceCapabilities).toHaveBeenCalledOnce()
  })

  it('switches on without asking; under a parent that is still off the answer reads off through it', async () => {
    vi.mocked(setInstanceCapability).mockResolvedValueOnce(entry('core.a.e', 'Echo', false, false))
    const w = await mountPanel()
    await toggle(w, 'core.a.e')
    expect(confirmAsk).not.toHaveBeenCalled()
    expect(setInstanceCapability).toHaveBeenCalledWith('core.a.e', false)
    expect(stateOf(w, 'core.a.e')).toBe(t('capabilities.offThrough', { parent: 'Alpha EN' }))
  })

  it('re-reads the register after switching a parent, so its children show the server’s new state', async () => {
    const after = register().map((e) =>
      e.id === 'core.a' ? entry('core.a', 'Alpha', false, true) : e.id === 'core.a.d' ? entry('core.a.d', 'Delta', false, true) : e,
    )
    vi.mocked(setInstanceCapability).mockResolvedValueOnce(after[1])
    const w = await mountPanel()
    vi.mocked(listInstanceCapabilities).mockResolvedValueOnce(after)
    await toggle(w, 'core.a')
    expect(listInstanceCapabilities).toHaveBeenCalledTimes(2)
    expect(stateOf(w, 'core.a')).toBe(t('capabilities.on'))
    expect(stateOf(w, 'core.a.d')).toBe(t('capabilities.on'))
    expect(stateOf(w, 'core.a.b.c')).toBe(t('capabilities.offThrough', { parent: 'Bravo EN' }))
  })

  it('says in words that the server no longer knows the entry, and that any other write failed', async () => {
    confirmAsk.mockResolvedValue(true)
    vi.mocked(setInstanceCapability).mockRejectedValueOnce(httpError(404, 'capability_not_found'))
    const w = await mountPanel()
    await toggle(w, 'core.x')
    expect(w.find('.capability-error').text()).toBe(t('capabilities.notFound', { title: 'Ex EN' }))
    expect(stateOf(w, 'core.x')).toBe(t('capabilities.on'))

    vi.mocked(setInstanceCapability).mockRejectedValueOnce(httpError(500))
    await toggle(w, 'core.x')
    expect(w.find('.capability-error').text()).toBe(t('capabilities.switchFailed', { title: 'Ex EN' }))
  })

  it('a session that is not the Server Owner’s gets the read-only notice, not an error', async () => {
    vi.mocked(listInstanceCapabilities).mockRejectedValue(httpError(403, 'not_server_owner'))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
    const w = mount(CapabilitiesPanel, { global: { plugins: [router, i18n], stubs } })
    await flushPromises()
    expect(w.text()).toContain(t('capabilities.notOwner'))
    expect(w.findAll('.capability')).toHaveLength(0)
  })
})
