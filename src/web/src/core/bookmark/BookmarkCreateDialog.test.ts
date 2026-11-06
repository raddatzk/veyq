// @vitest-environment happy-dom
// Creating a bookmark (uc.sharing.keep-a-received-link, ADR 0120 decision 4): a pasted link checked as it is typed - a
// malformed one refused with its reason, a foreign one taken - the name following the link's host until the member names
// it, and the link with its password sealed into the node's meta in the same create, through the real meta codec.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const state = vi.hoisted(() => ({ meta: null as string | null, kinds: [] as string[], names: [] as string[], fail: null as unknown }))
vi.mock('../node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [] as unknown[], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})
vi.mock('../node/tree', async (orig) => ({
  ...(await orig<object>()),
  createContentNode: vi.fn(async (scopeId: string, _e: unknown, _p: unknown, kind: string, name: string, sealer: (k: Uint8Array, n: { scopeId: string; nodeId: string }) => string) => {
    if (state.fail) throw state.fail
    state.kinds.push(kind)
    state.names.push(name)
    state.meta = sealer(KEY, { scopeId, nodeId: NODE })
    return { nodeId: NODE, contentKey: KEY, epoch: 0, scopeEpoch: 0 }
  }),
}))

import { b64url } from '@veyq/crypto/base64'
import BookmarkCreateDialog from './BookmarkCreateDialog.vue'
import { unsealNodeMeta } from '../node/nodeMeta'
import { SHARE_BOOKMARK_KIND } from '../node/kind'
import { treeStore } from '../node/treeStore'
import { bookmarkOf } from './bookmarkMeta'
import { realI18n, t } from '../../test/i18n'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { treeNode } from '../../test/models'

const KEY = new Uint8Array(32).fill(6)
const NODE = '5c09e000-0000-4000-8000-0000000000dd'
const SCOPE = '5c09e000-0000-4000-8000-0000000000ee'
const KSHARE = b64url(new Uint8Array(32).fill(2))
const FOREIGN = `https://photos.other.example/s/${'F'.repeat(43)}#k=${KSHARE}`

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  state.meta = null
  state.kinds = []
  state.names = []
  state.fail = null
  treeStore.nodes = []
})

async function open() {
  const w = mount(BookmarkCreateDialog, {
    props: { kind: SHARE_BOOKMARK_KIND, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}
const field = (id: string) => document.body.querySelector<HTMLInputElement>(`[data-testid="${id}"]`)!
const password = () => document.body.querySelector<HTMLInputElement>('input.veyq-password__input')!
const problem = () => document.body.querySelector('[data-testid="bookmark-link-problem"]')?.textContent?.trim() ?? null
const submit = () => [...document.body.querySelectorAll<HTMLButtonElement>('button[type="submit"]')][0]!
async function set(el: HTMLInputElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input'))
  await flushPromises()
}

describe('BookmarkCreateDialog', () => {
  it('takes a link of another instance, names it after the host, and seals the normalized link and its password', async () => {
    const w = await open()
    expect(submit().disabled, 'nothing to keep yet').toBe(true)
    await set(field('bookmark-link'), ` ${FOREIGN}&report `)
    expect(problem()).toBeNull()
    expect(field('bookmark-name').value).toBe(t('node.bookmark.defaultName', { host: 'photos.other.example' }))
    await set(password(), 'correct horse')
    submit().click()
    await flushPromises()
    expect(state.kinds).toEqual([SHARE_BOOKMARK_KIND])
    expect(state.names).toEqual(['Link from photos.other.example'])
    const kept = bookmarkOf(unsealNodeMeta(KEY, { scopeId: SCOPE, nodeId: NODE }, state.meta))
    expect(kept?.link.link, 'the &report and the spaces are gone').toBe(FOREIGN)
    expect(kept?.password).toBe('correct horse')
    expect(w.emitted('done')).toHaveLength(1)
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('keeps a name the member typed, whatever link follows, and keeps no password where none was given', async () => {
    await open()
    await set(field('bookmark-name'), 'Holiday photos')
    await set(field('bookmark-link'), FOREIGN)
    expect(field('bookmark-name').value).toBe('Holiday photos')
    submit().click()
    await flushPromises()
    expect(state.names).toEqual(['Holiday photos'])
    expect(bookmarkOf(unsealNodeMeta(KEY, { scopeId: SCOPE, nodeId: NODE }, state.meta))?.password).toBeNull()
  })

  it('numbers the suggested name past a sibling that already holds it', async () => {
    treeStore.nodes = [treeNode({ id: 'n1', kind: SHARE_BOOKMARK_KIND, name: 'Link from photos.other.example', parentId: null })]
    await open()
    await set(field('bookmark-link'), FOREIGN)
    expect(field('bookmark-name').value).toBe('Link from photos.other.example (2)')
    submit().click()
    await flushPromises()
    expect(state.names).toEqual(['Link from photos.other.example (2)'])
  })

  it('says what is wrong with a malformed link, and creates nothing', async () => {
    await open()
    for (const [input, key] of [
      ['photos.other.example', 'notAUrl'],
      [`http://photos.other.example/s/${'F'.repeat(43)}#k=${KSHARE}`, 'notHttps'],
      [`https://photos.other.example/x/${'F'.repeat(43)}#k=${KSHARE}`, 'notAShare'],
      [`https://photos.other.example/s/${'F'.repeat(43)}`, 'noKey'],
      [`https://ada:pw@photos.other.example/s/${'F'.repeat(43)}#k=${KSHARE}`, 'credentials'],
    ] as const) {
      await set(field('bookmark-link'), input)
      expect(problem(), input).toBe(t(`node.bookmark.problem.${key}`))
      expect(submit().disabled, input).toBe(true)
    }
    await set(field('bookmark-link'), FOREIGN)
    expect(problem(), 'control').toBeNull()
    expect(submit().disabled).toBe(false)
    expect(state.kinds).toEqual([])
  })

  it('refuses a name a sibling already has before sending, and says a failed create', async () => {
    treeStore.nodes = [treeNode({ id: 'n1', kind: SHARE_BOOKMARK_KIND, name: 'Taken', parentId: null })]
    const w = await open()
    await set(field('bookmark-link'), FOREIGN)
    await set(field('bookmark-name'), 'Taken')
    submit().click()
    await flushPromises()
    expect(state.kinds).toEqual([])
    expect(w.emitted('done')).toBeUndefined()

    await set(field('bookmark-name'), 'Free')
    state.fail = new Error('offline')
    submit().click()
    await flushPromises()
    expect(document.body.textContent).toContain(t('node.bookmark.createFailed'))
    expect(w.emitted('done')).toBeUndefined()
  })

  it('says, before saving, that whoever opens this place can open the link', async () => {
    await open()
    expect(document.body.querySelector('[data-testid="bookmark-who-can-open"]')?.textContent?.trim()).toBe(t('node.bookmark.whoCanOpen'))
  })
})
