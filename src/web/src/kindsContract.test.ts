// @vitest-environment happy-dom
// The kinds contract (`contracts/platform/kinds/`, its schema `kinds.schema.json`): every fact a file states of a kind is
// what the web answers - the registry the composition root filled (`src/test/setup.ts`, as `main.ts`) and each
// application's rights function - and the backend's `KindsContractTest` holds the server's seams to the same files, so
// the two clients cannot drift apart unseen. Beside `applications.test.ts` because it reads every application at once
// (a test file may import them; core may not).
//
// Both directions: every kind of the contract is registered with its facts, and every registered kind is in a file.
// Asked per kind: its application, its placement over the root and every kind of the contract (and one no file names),
// its cover, its share roles (the share dialog and the drop page in the registry, and the rights function's offers for a
// member with every leaf on), what its creation needs (the level's "New" entry with that leaf off), and what a link's
// guest may do around it (a `READ_WRITE` guest's create in a folder, the move core lets out of a container). What a
// `WRITE_ONLY` guest creates is the backend's alone: the web's one place for it is the application's own drop page.
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { registry, type LevelActionContext } from './core/applications/registry'
import { folderRights, nodeRights, targetOf, type Access, type MemberAccess, type NodeRights, type RightsTarget, type SourceNode, type TreeSource } from './core/access'
import { FOLDER_KIND } from './core/node/kind'
import { memoryRights } from './features/memories/rights'
import { depotRights } from './features/depot/rights'
import { expensesRights } from './features/expenses/rights'
import { CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX } from './features/platform/capabilities'
import { guestAccessOf, memberAccessOf } from './test/models'

interface KindFacts {
  placement: { root: boolean; parents: string[] }
  cover: boolean
  createNeeds: string | null
  shareRoles: ('READ' | 'READ_WRITE' | 'WRITE_ONLY')[]
  guest: { creatableInFolder: boolean; container: boolean; writeOnlyCreates: string[] }
}
interface Kind extends KindFacts { kind: string; owner: string }

const dir = resolve(__dirname, '../../../contracts/platform/kinds')
const files = readdirSync(dir).filter((f) => f.endsWith('.json') && !f.endsWith('.schema.json')).sort()
const kinds: Kind[] = files.flatMap((file) => {
  const doc = JSON.parse(readFileSync(resolve(dir, file), 'utf8')) as { owner: string; kinds: Record<string, KindFacts> }
  if (`${doc.owner}.json` !== file) throw new Error(`${file}: a kinds file is named for its owner, not ${doc.owner}`)
  return Object.entries(doc.kinds).map(([kind, facts]) => ({ kind, owner: doc.owner, ...facts }))
})
const byKind = new Map(kinds.map((k) => [k.kind, k]))
/** Every kind the contract names, and one it does not. */
const probes = [...kinds.map((k) => k.kind), 'NOT_A_KIND']

/** Each owner's rights function, by the owner's name (core answers for FOLDER). A new application adds its own here. */
type Rights = (access: Access, at: RightsTarget) => Partial<Record<keyof NodeRights, boolean>>
const RIGHTS: Record<string, Rights> = { core: folderRights, memories: memoryRights, depot: depotRights, expenses: expensesRights }

/** The access flag a member's level reads for each capability a creation may need (the server's AND along the path). */
const LEAF: Record<string, keyof Pick<MemberAccess, 'publicShares' | 'publicSharesContribute' | 'publicSharesDropBox'>> = {
  [CORE_PUBLIC_SHARES]: 'publicShares',
  [CORE_PUBLIC_SHARES_CONTRIBUTE]: 'publicSharesContribute',
  [CORE_PUBLIC_SHARES_DROP_BOX]: 'publicSharesDropBox',
}

/** A tree of one folder `f`: a member's (rootId null, the folder at the scope root) or a guest's (the link's root). */
function treeOfOneFolder(rootId: string | null): { tree: TreeSource; folder: SourceNode } {
  const folder: SourceNode = { id: 'f', parentId: null, kind: FOLDER_KIND }
  const tree: TreeSource = {
    rootId,
    node: (id) => (id === folder.id ? folder : null),
    children: () => [],
    keyOf: () => null,
    nameOf: () => 'f',
    inIdentityFolder: () => false,
    linkTo: () => '/',
  }
  return { tree, folder }
}

/** Whether `access` is offered a create of `kind` at the folder of `tree`: core's folder create for FOLDER, else any entry of the
 *  level's "New" menu a kind of the application declared for it (its create entry, or an action such as Depot's uploads). */
function createOffered(kind: string, access: Access, tree: TreeSource, folder: SourceNode): boolean {
  if (kind === FOLDER_KIND) return folderRights(access, targetOf(tree, folder)).createFolder
  const ctx: LevelActionContext = { access, tree, folder: folder as LevelActionContext['folder'], epochs: null }
  return registry.levelMenu().some((e) => e.kind === kind && e.allowed(ctx))
}

describe('the kinds contract and the web agree', () => {
  it('is whole: every kind of a file is registered, every registered kind is in a file', () => {
    expect(files).toEqual(['core.json', 'depot.json', 'expenses.json', 'memories.json']) // the anchor
    expect(byKind.size, 'a kind appears in one file only').toBe(kinds.length)
    expect([...registry.kinds()].sort()).toEqual([...byKind.keys()].sort())
  })

  it.each(kinds)('$kind belongs to its owner and lives where its file says', (k) => {
    expect(registry.kind(k.kind).supported).toBe(true)
    expect(registry.applicationOf(k.kind)).toBe(k.owner === 'core' ? null : k.owner)
    expect(RIGHTS[k.owner], `a rights function of ${k.owner}`).toBeTypeOf('function')
    expect(registry.placementAllows(k.kind, null), `${k.kind} at the root`).toBe(k.placement.root)
    for (const parent of probes) {
      expect(registry.placementAllows(k.kind, parent), `${k.kind} in a ${parent}`).toBe(k.placement.parents.includes(parent))
    }
    const d = registry.kind(k.kind)
    expect(d.supported && !!d.cover, `${k.kind} takes a cover`).toBe(k.cover)
  })

  it.each(kinds)('$kind roots the links its file says', (k) => {
    const d = registry.kind(k.kind)
    expect(d.supported && !!d.shareDialog, `${k.kind} brings a share dialog`).toBe(k.shareRoles.length > 0)
    expect(registry.shareBodyFor(k.kind) !== null, `${k.kind} has a drop page`).toBe(k.shareRoles.includes('WRITE_ONLY'))
    // What the kind's rights function offers a member with every leaf on, at a node of the kind in a folder.
    const at: RightsTarget = { kind: k.kind, inIdentityFolder: false, shareable: k.shareRoles.length > 0, parentKind: FOLDER_KIND }
    const r = RIGHTS[k.owner](memberAccessOf(), at)
    const offered = [r.share && 'READ', r.shareContribute && 'READ_WRITE', r.shareUpload && 'WRITE_ONLY'].filter(Boolean)
    expect(offered.sort()).toEqual([...k.shareRoles].sort())
  })

  it.each(kinds)('creating a $kind needs what its file says', (k) => {
    const { tree, folder } = treeOfOneFolder(null)
    const viaLevel = k.kind === FOLDER_KIND || registry.levelMenu().some((e) => e.kind === k.kind)
    if (!viaLevel) {
      // Created through no level entry (a chapter, inside its Memory): the web has no generic door to hold a need to.
      expect(k.createNeeds, `${k.kind} is created at no level, so its file can state no need the web checks`).toBeNull()
      return
    }
    expect(createOffered(k.kind, memberAccessOf(), tree, folder), `control: a member creates a ${k.kind}`).toBe(true)
    if (k.createNeeds !== null) expect(LEAF[k.createNeeds], `the access flag of ${k.createNeeds}`).toBeDefined()
    for (const [capability, flag] of Object.entries(LEAF)) {
      expect(createOffered(k.kind, memberAccessOf({ [flag]: false }), tree, folder), `a ${k.kind} with ${capability} off`)
        .toBe(k.createNeeds !== capability)
    }
  })

  it.each(kinds)('a guest does around a $kind what its file says', (k) => {
    // A folder link's READ_WRITE guest, at the link's root folder.
    const { tree, folder } = treeOfOneFolder('f')
    expect(createOffered(k.kind, guestAccessOf('READ_WRITE'), tree, folder), `a guest creates a ${k.kind} in a folder`)
      .toBe(k.guest.creatableInFolder)
    // Core lets a READ_WRITE guest move a node only out of a guest container (`GuestNodeRules`); any kind of child.
    for (const child of probes) {
      const at: RightsTarget = { kind: child, inIdentityFolder: false, shareable: false, parentKind: k.kind }
      expect(nodeRights(guestAccessOf('READ_WRITE'), at).move, `a guest moves a ${child} out of a ${k.kind}`).toBe(k.guest.container)
    }
  })
})
