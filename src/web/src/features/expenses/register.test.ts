// @vitest-environment happy-dom
// Expenses' registration (ADR 0079, ADR 0108): the Pot kind with its page, its create dialog and "New Pot" entry (a
// member's only), its READ / READ_WRITE share dialog, its re-key, download and import, its review of a reported entry, and
// its application entry, which the switch reads. A Pot's row in a folder names its base currency (CR decision 8), opened
// under the Pot's own key — and nothing where that key does not open it.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { createRegistry, registry as applicationRegistry, type LevelActionContext } from '../../core/applications/registry'
import { FOLDER_KIND } from '../../core/node/kind'
import { ShareRole } from '../../api/generated/sharing'
import { guestAccessOf, memberAccessOf, treeNode } from '../../test/models'
import { mountRows } from '../../test/levelRows'
import type { Access, GuestAccess, TreeSource } from '../../core/access'
import type { TreeNode } from '../../core/node/tree'
import { EXPENSES } from '../platform/capabilities'
import { registerExpenses } from './register'
import { POT_KIND } from './kinds'
import { potRows } from './levelRows'
import { rekeyPotContent } from './rekey'
import { exportPot, importPot, validatePotContent } from './exportImport'
import PotDetail from './components/PotDetail.vue'
import PotCreateDialog from './components/PotCreateDialog.vue'
import PotShareDialog from './components/PotShareDialog.vue'
import PotEntryReview from './components/PotEntryReview.vue'
import { POT_ID, SCOPE, potNode } from './potTestUtil'
import { t } from '../../test/i18n'

describe('registerExpenses', () => {
  it('registers the Pot kind with its page, dialogs, rows, re-key, download and import', () => {
    const r = createRegistry()
    registerExpenses(r)
    expect(r.kind(POT_KIND)).toMatchObject({
      supported: true, kind: POT_KIND, icon: 'wallet', shareable: true, detail: PotDetail, shareDialog: PotShareDialog, createDialog: PotCreateDialog,
      deleteTitle: 'expenses.delete', deleteCopy: 'expenses.deleteConfirm', nounWithArticle: 'expenses.aPot',
      list: { rows: potRows, tabLabel: 'expenses.title' }, placement: { root: true, parents: [FOLDER_KIND] },
      rekeyContent: rekeyPotContent, exportContent: exportPot, importContent: importPot, validateContent: validatePotContent,
      contentSchema: { application: 'expenses', version: 1 }, importStreams: true,
      // A copy is the download and the import without the file, so it loses what the download leaves out: the history.
      copyLosses: ['expenses.copyLoss'],
    })
    expect(r.applicationOf(POT_KIND)).toBe(EXPENSES)
    expect(r.applications()).toEqual([{ id: EXPENSES, name: 'expenses.title' }])
    expect(r.routeOf(POT_KIND)).toEqual({ slug: 'expenses', group: 'application:expenses' })
    expect(r.itemReviews()).toEqual([PotEntryReview])
    expect(r.placementAllows(POT_KIND, FOLDER_KIND)).toBe(true)
    expect(r.placementAllows(POT_KIND, POT_KIND)).toBe(false)
  })

  it('the web client\'s registry holds it beside the other applications (the composition root)', () => {
    expect(applicationRegistry.applicationOf(POT_KIND)).toBe(EXPENSES)
  })

  it('"New Pot" is a member\'s, ready once the keys are open — never a guest\'s, nor while Expenses is off', () => {
    const r = createRegistry()
    registerExpenses(r)
    const entry = r.levelMenu().find((m) => m.create === POT_KIND)!
    expect(entry).toMatchObject({ label: 'expenses.create.entry', icon: 'wallet', order: 40 })
    const tree = { node: () => null, children: () => [], keyOf: () => null, nameOf: () => '', inIdentityFolder: () => false, linkTo: () => '', rootId: null } as unknown as TreeSource
    const at = (access: LevelActionContext['access'], epochs: LevelActionContext['epochs'] = null): LevelActionContext => ({ access, tree, folder: null, epochs })
    expect(entry.allowed(at(memberAccessOf()))).toBe(true)
    expect(entry.enabled!(at(memberAccessOf()))).toBe(false)
    expect(entry.allowed(at(memberAccessOf({ applicationsOff: new Set([EXPENSES]) })))).toBe(false)
    expect(entry.allowed(at(guestAccessOf(ShareRole.ReadWrite)))).toBe(false)
  })
})

describe('a Pot\'s row', () => {
  const key = v1.randomBytes(32)
  const pot = potNode(key, { currency: 'CHF', minorUnits: 2 })
  const tree = (keyOf: (id: string) => Uint8Array | null): TreeSource<TreeNode> =>
    ({ rootId: null, node: (id: string) => (id === POT_ID ? pot : null), children: () => [pot], keyOf, nameOf: () => 'Trip', inIdentityFolder: () => false, linkTo: () => '' }) as unknown as TreeSource<TreeNode>
  const ctx = (keyOf: (id: string) => Uint8Array | null, access: Access = memberAccessOf({ scopeId: SCOPE })) => ({
    folder: null, nodes: [pot], epochs: null, access, tree: tree(keyOf), viewMode: 'list' as const, liveTick: undefined,
  })

  it('names the Pot and its base currency, and opens it on a click', async () => {
    const h = mountRows(potRows, ctx(() => key))
    const row = h.source().row(pot)!
    expect(row).toMatchObject({ icon: 'wallet', title: 'Trip', meta: t('expenses.kindPot'), details: [{ id: 'currency', text: 'CHF' }] })
    expect(row.actions.filter((a) => a.shown !== false).map((a) => a.id)).toEqual(['rename', 'move'])
    row.primary.onClick()
    expect(h.events.open).toEqual([pot])
  })

  it('leaves the detail out where the key does not open the meta, and offers a guest no action', () => {
    expect(mountRows(potRows, ctx(() => v1.randomBytes(32))).source().row(pot)!.details).toEqual([])
    expect(mountRows(potRows, ctx(() => null)).source().row(treeNode({ ...pot, wrappedMeta: null }))!.details).toEqual([])
    const guest = mountRows(potRows, ctx(() => key, guestAccessOf(ShareRole.ReadWrite, { head: { scopeId: SCOPE } as unknown as GuestAccess['head'] })))
    const row = guest.source().row(pot)!
    expect(row.details).toEqual([{ id: 'currency', text: 'CHF' }])
    expect(row.actions.filter((a) => a.shown !== false)).toEqual([])
    expect(row.selection).toEqual({ move: false, delete: false })
  })
})
