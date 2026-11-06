// @vitest-environment happy-dom
// Reporting a bookmark (ADR 0120 decision 6): its Report is its share's, handed to the share page where the share lives
// with the `report` fragment parameter - never this instance's moderators, who are handed the bookmark's NAME alone, from
// its own entry and from every other way core reports a node (a selection). Driven through core's real actions
// (`useCoreNodeActions`), as a level and a node page draw them.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn(async () => {}) }))
// The instance publishes a moderation key, so core's report is offered; the dialog itself is not this test's.
vi.mock('../report/report', async (orig) => {
  const { ref } = await import('vue')
  return { ...(await orig<object>()), useReportOffered: () => ref(true), openReport: vi.fn() }
})

import { defineComponent, h, type App } from 'vue'
import { mount } from '@vue/test-utils'
import { b64url } from '@veyq/crypto/base64'
import { useCoreNodeActions, type CoreNodeActions } from '../node/coreNodeActions'
import { sealNodeMeta } from '../node/nodeMeta'
import { FOLDER_KIND, SHARE_BOOKMARK_KIND } from '../node/kind'
import { reportNodes } from '../report/reportActions'
import { openReport } from '../report/report'
import { keptLink } from './bookmarkLink'
import { bookmarkMetaObject } from './bookmarkMeta'
import { bookmarkOpenRequest } from './openBookmark'
import { realI18n } from '../../test/i18n'
import { memberAccessOf, memberTreeOf, treeNode } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import type { TreeNode } from '../node/tree'
import type { Router } from 'vue-router'

const SCOPE = '5c09e000-0000-4000-8000-0000000000ab'
const EPOCHS = epochsOf(new Uint8Array(32).fill(4))
const KEY = new Uint8Array(32).fill(8)
const SHARE = 'R'.repeat(43)
const KSHARE = b64url(new Uint8Array(32).fill(1))
const FOLDER = treeNode({ id: 'f0000000-0000-4000-8000-000000000001', kind: FOLDER_KIND, name: 'Links', parentId: null })
const bookmark = (link: string | null, password: string | null = null): TreeNode => {
  const id = 'b0000000-0000-4000-8000-000000000002'
  const wrappedMeta = link ? sealNodeMeta(KEY, { scopeId: SCOPE, nodeId: id }, bookmarkMetaObject(keptLink(link)!, password)) : null
  return treeNode({ id, kind: SHARE_BOOKMARK_KIND, name: 'Holiday', parentId: FOLDER.id, wrappedMeta })
}

function harness(node: TreeNode, push = vi.fn()): { actions: CoreNodeActions; push: typeof push } {
  let actions: CoreNodeActions | null = null
  const tree = { ...memberTreeOf(() => [FOLDER, node], { scopeId: SCOPE, epochs: EPOCHS }), keyOf: () => KEY }
  mount(defineComponent({
    setup() {
      actions = useCoreNodeActions(() => memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS }), () => tree, () => [node], {})
      return () => h('div')
    },
  }), { global: { plugins: [realI18n(), { install: (app: App) => { app.config.globalProperties.$router = { push } as unknown as Router } }] } })
  return { actions: actions!, push }
}
const ids = (as: { id: string }[]) => as.map((a) => a.id).filter((id) => id.startsWith('report'))

beforeEach(() => {
  vi.clearAllMocks()
  bookmarkOpenRequest.value = null
})

describe('a bookmark\'s Report', () => {
  it('hands the share\'s report to this instance\'s share page, in this tab, with the report parameter', () => {
    const node = bookmark(`${window.location.origin}/s/${SHARE}#k=${KSHARE}`)
    const { actions, push } = harness(node)
    for (const list of [actions.row(node), actions.header(node)]) {
      expect(ids(list), 'the name first, the share last before delete').toEqual(['report-name', 'report'])
    }
    actions.row(node).find((a) => a.id === 'report')!.onClick()
    expect(push).toHaveBeenCalledWith({ path: `/s/${SHARE}`, hash: `#k=${KSHARE}&report` })
    expect(vi.mocked(openReport), 'nothing goes to this instance\'s moderators').not.toHaveBeenCalled()
  })

  it('hands a foreign share\'s report to its own page in a new tab, through the password dialog where it has one', () => {
    const open = vi.fn()
    vi.stubGlobal('open', open)
    const foreign = `https://photos.other.example/s/${SHARE}#k=${KSHARE}`
    const plain = bookmark(foreign)
    harness(plain).actions.row(plain).find((a) => a.id === 'report')!.onClick()
    expect(open).toHaveBeenCalledWith(`${foreign}&report`, '_blank', 'noopener,noreferrer')
    const locked = bookmark(foreign, 'pw')
    harness(locked).actions.row(locked).find((a) => a.id === 'report')!.onClick()
    expect(bookmarkOpenRequest.value).toMatchObject({ url: `${foreign}&report`, report: true, password: 'pw' })
    expect(vi.mocked(openReport)).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('reports the bookmark\'s name to this instance, never its key', () => {
    const node = bookmark(`https://photos.other.example/s/${SHARE}#k=${KSHARE}`)
    harness(node).actions.row(node).find((a) => a.id === 'report-name')!.onClick()
    const request = vi.mocked(openReport).mock.calls[0]![0]
    expect(request.targets.map((x) => [x.kind, 'id' in x ? x.id : null])).toEqual([['NAME', node.id]])
    expect(request.nameOnly, 'no second choice of the same name').toBeUndefined()
  })

  it('where its link does not read, Report is the name\'s alone', () => {
    const node = bookmark(null)
    const { actions } = harness(node)
    expect(ids(actions.row(node))).toEqual(['report'])
    actions.row(node).find((a) => a.id === 'report')!.onClick()
    expect(vi.mocked(openReport).mock.calls[0]![0].targets.map((x) => x.kind)).toEqual(['NAME'])
  })

  it('a selection with a bookmark in it reports the bookmark by its name and every other node by its key (control)', () => {
    const node = bookmark(`https://photos.other.example/s/${SHARE}#k=${KSHARE}`)
    const tree = { ...memberTreeOf(() => [FOLDER, node], { scopeId: SCOPE, epochs: EPOCHS }), keyOf: () => KEY }
    reportNodes(memberAccessOf({ scopeId: SCOPE, epochs: EPOCHS }), tree, [FOLDER, node], null)
    expect(vi.mocked(openReport).mock.calls[0]![0].targets.map((x) => [x.kind, 'id' in x ? x.id : null])).toEqual([['NODE', FOLDER.id], ['NAME', node.id]])
  })
})
