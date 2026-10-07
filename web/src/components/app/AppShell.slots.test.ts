// @vitest-environment happy-dom
// The shell's two application slots against a STUBBED registry (a fresh `createRegistry()` per test behind a
// delegating `registry` export): `AppTopbar` renders the `topbarTray` list, `AppSidebar` the `sidebarPins` list (rail
// and mobile sheet). The case that matters: an application that fills NEITHER leaves a working shell — no throw, no
// tray, no pinned section — and the same shell with an item added renders it (the live control); two items render
// in their `order`, and ONE item renders exactly the DOM the single-claim slot rendered before the lists (P4a). The
// real registry with the real Memories fills is covered in `AppTopbar.test.ts` and `AppSidebar.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'

const holder = vi.hoisted(() => ({ reg: null as null | import('../../core/applications/registry').Registry }))
vi.mock('../../core/applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../core/applications/registry')>()
  // `src/test/setup.ts` registers the real applications into whatever this resolves to before the first test;
  // each test then swaps `holder.reg` for the registry it wants.
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    // A Proxy, not a method list: a method the registry gains later is delegated too, instead of being silently
    // missing here (a list of four methods once made `registerLifecycle` "not a function" for the whole file).
    registry: new Proxy({} as import('../../core/applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})
vi.mock('../../features/spaces/scopes', () => ({
  listMyTenants: vi.fn(),
  listMyGroups: vi.fn(),
  openMyTenantIcon: vi.fn(),
}))
vi.mock('../../features/account/profile/profile', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../features/account/profile/profile')>()),
  getMyProfile: vi.fn(),
}))

import AppTopbar from './AppTopbar.vue'
import AppSidebar from './AppSidebar.vue'
import { createRegistry } from '../../core/applications/registry'
import { listMyTenants, listMyGroups, openMyTenantIcon } from '../../features/spaces/scopes'
import { getMyProfile } from '../../features/account/profile/profile'
import { clearActiveSpace } from '../../app/activeSpace'
import { realI18n } from '../../test/i18n'
import { memberProfile, myTenant } from '../../test/models'

const Tray = defineComponent({ setup: () => () => h('button', { class: 'tray-probe' }, 'tray') })
const Tray2 = defineComponent({ setup: () => () => h('button', { class: 'tray-probe' }, 'tray2') })
const Pins = defineComponent({
  props: ['variant'],
  emits: ['navigate'],
  setup: (p, { emit }) => () => h('a', { class: `pins-probe pins-${p.variant}`, onClick: () => emit('navigate') }, p.variant),
})
const Pins2 = defineComponent({
  props: ['variant'],
  setup: (p) => () => h('a', { class: `pins-probe pins2-${p.variant}` }, `${p.variant}2`),
})

const PopoverStub = { template: '<div class="popover-stub"><slot /></div>', methods: { toggle() {}, hide() {} } }
const DialogStub = { props: ['visible'], template: '<div v-if="visible" class="dialog-stub"><slot /></div>' }

async function mountSidebar() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash: '' })
  await router.isReady()
  const w = mount(AppSidebar, {
    global: { plugins: [router, realI18n()], stubs: { ProfileMenu: true, Popover: PopoverStub, Dialog: DialogStub } },
  })
  await flushPromises()
  return w
}
const mountTopbar = () =>
  mount(AppTopbar, {
    global: {
      plugins: [realI18n()],
      stubs: { InvitationsBadge: true, Breadcrumb: true },
    },
  })

beforeEach(() => {
  vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't-1', domain: '127.0.0.1' })])
  vi.mocked(listMyGroups).mockResolvedValue([])
  vi.mocked(openMyTenantIcon).mockResolvedValue(null)
  vi.mocked(getMyProfile).mockResolvedValue(memberProfile({ username: '' }))
  clearActiveSpace()
  holder.reg = createRegistry()
})

describe('an application that fills no shell slot', () => {
  it('leaves the top bar working: the actions row is there, with no tray in it', async () => {
    const w = mountTopbar()
    expect(w.find('.workspace-topbar-actions').exists()).toBe(true)
    expect(w.find('.tray-probe').exists()).toBe(false)
  })

  it('leaves the sidebar working: spaces and groups render, no pinned section, no sheet rows', async () => {
    const w = await mountSidebar()
    expect(w.find('.sidebar-personal-link').exists()).toBe(true)
    expect(w.find('.sidebar-groups-section').exists()).toBe(true)
    expect(w.find('.pins-probe').exists()).toBe(false)
    await w.get('.mobile-navigation-button').trigger('click')
    await flushPromises()
    expect(w.find('.pins-probe').exists()).toBe(false)
  })
})

describe('an application that adds to the slots', () => {
  it('the top bar mounts the added tray item in its actions row', async () => {
    holder.reg!.addSlotItem('topbarTray', 'app.tray', Tray)
    const w = mountTopbar()
    expect(w.find('.workspace-topbar-actions .tray-probe').exists()).toBe(true)
  })

  it('the sidebar mounts the added pins in the rail AND in the mobile sheet; following a sheet link closes it', async () => {
    holder.reg!.addSlotItem('sidebarPins', 'app.pins', Pins)
    const w = await mountSidebar()
    expect(w.findAll('.pins-probe').map((e) => e.text())).toEqual(['rail'])
    await w.get('.mobile-navigation-button').trigger('click')
    await flushPromises()
    expect(w.findAll('.pins-probe').map((e) => e.text())).toEqual(['rail', 'sheet'])
    await w.get('.pins-sheet').trigger('click') // the application emitted `navigate`
    await flushPromises()
    expect(w.find('.pins-sheet').exists()).toBe(false) // closeMobileMenu ran
  })
})

describe('two items in a list slot', () => {
  it('the top bar renders both, in their order, not in registration order', async () => {
    holder.reg!.addSlotItem('topbarTray', 'b.tray', Tray2, 2)
    holder.reg!.addSlotItem('topbarTray', 'a.tray', Tray, 1)
    const w = mountTopbar()
    expect(w.findAll('.workspace-topbar-actions .tray-probe').map((e) => e.text())).toEqual(['tray', 'tray2'])
  })

  it('the sidebar renders both, in their order, in the rail and in the sheet', async () => {
    holder.reg!.addSlotItem('sidebarPins', 'b.pins', Pins2, 2)
    holder.reg!.addSlotItem('sidebarPins', 'a.pins', Pins, 1)
    const w = await mountSidebar()
    expect(w.findAll('.pins-probe').map((e) => e.text())).toEqual(['rail', 'rail2'])
    await w.get('.mobile-navigation-button').trigger('click')
    await flushPromises()
    expect(w.findAll('.pins-probe').map((e) => e.text())).toEqual(['rail', 'rail2', 'sheet', 'sheet2'])
  })
})

// The DOM the single-claim slot rendered (the templates before P4a: `<component :is="tray" v-if="tray" />` as the last
// child of the actions row; `<component :is="sidebarPins" v-if="sidebarPins" variant="rail" />` between the
// Personal-space nav and the groups section, and the `sheet` one as the last child of the sheet), written out by hand:
// with ONE item the list renders no wrapper and nothing else around it.
describe('one item renders the DOM of the former single-claim slot', () => {
  it('top bar: the actions row is the badge, then the tray item, nothing else', async () => {
    holder.reg!.addSlotItem('topbarTray', 'app.tray', Tray)
    const w = mountTopbar()
    expect(w.get('.workspace-topbar-actions').element.innerHTML.replace(/<!--.*?-->/g, '')).toBe(
      '<invitations-badge-stub></invitations-badge-stub><button class="tray-probe">tray</button>',
    )
  })

  it('sidebar: the pins sit between the Personal nav and the groups section in the rail, and close the sheet', async () => {
    holder.reg!.addSlotItem('sidebarPins', 'app.pins', Pins)
    const w = await mountSidebar()
    const rail = w.get('.pins-rail').element
    expect(rail.outerHTML).toBe('<a class="pins-probe pins-rail">rail</a>')
    expect(rail.previousElementSibling!.tagName).toBe('NAV')
    expect(rail.nextElementSibling!.className).toBe('sidebar-section sidebar-groups-section')
    await w.get('.mobile-navigation-button').trigger('click')
    await flushPromises()
    const sheet = w.get('.pins-sheet').element
    expect(sheet.outerHTML).toBe('<a class="pins-probe pins-sheet">sheet</a>')
    expect(sheet.previousElementSibling!.tagName).toBe('SECTION')
    expect(sheet.nextElementSibling).toBeNull()
  })
})
