// @vitest-environment happy-dom
// Which shell the root picks, and for which route. Ported from `src/web/`'s AppShell shape (ADR 0065 — a port,
// not an import): a card route renders centred inside `.veyq-shell` > `.veyq-shell-inner`, the console renders
// full-width in `.admin-root` and centres itself.
//
// The cases mount the REAL route table — `routes` is exported for exactly this — so a route that loses its
// `meta.layout` is caught here rather than in a browser. Only the components are stubbed: this is a test about
// where a view is placed, never about what it renders.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type RouteRecordRaw } from 'vue-router'
import { createI18n } from 'vue-i18n'
import App from './App.vue'
import { routes } from './router'
import { serverState } from './shared/state/serverState'

const UNREACHABLE = 'This instance could not be reached.'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: { admin: { unreachable: UNREACHABLE } } },
})

/** The real records, keeping their real `path`/`name`/`meta`, wearing a stub view. Rebuilt field by field
 *  rather than spread: `RouteRecordRaw` is a union whose members disagree on `component` vs `components`, and
 *  a spread widens it into something assignable to neither. */
const stubbed: RouteRecordRaw[] = routes.map((record) => {
  if ('redirect' in record) return record
  const { path, name, meta } = record
  return { path, name, meta, component: { template: '<div class="stub-view" />' } }
})

async function mountAt(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: stubbed })
  await router.push(path)
  await router.isReady()
  return mount(App, { global: { plugins: [router, i18n] } })
}

describe('App shell', () => {
  beforeEach(() => {
    serverState.unreachable = false
  })

  it.each(['/login', '/setup'])('centres a card route (%s) in the public shell', async (path) => {
    const w = await mountAt(path)

    expect(w.find('.veyq-shell').exists()).toBe(true)
    expect(w.find('.veyq-shell-inner').exists()).toBe(true)
    expect(w.find('.admin-root').exists()).toBe(false)
    expect(w.find('.veyq-shell-inner .stub-view').exists()).toBe(true)
  })

  it('gives the console the full-width page shell and no card column', async () => {
    const w = await mountAt('/')

    expect(w.find('.admin-root').exists()).toBe(true)
    expect(w.find('.veyq-shell-inner').exists()).toBe(false)
    expect(w.find('.admin-root .stub-view').exists()).toBe(true)
  })

  it('renders the unreachable instance as a centred card, in place of the route', async () => {
    serverState.unreachable = true

    const w = await mountAt('/')

    expect(w.find('.veyq-shell-inner').exists()).toBe(true)
    expect(w.text()).toContain(UNREACHABLE)
    expect(w.find('.stub-view').exists()).toBe(false)
  })
})
