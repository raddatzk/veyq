import { describe, it, expect, vi } from 'vitest'
import { defineComponent } from 'vue'
import { createRegistry, RESERVED_ROUTE_SEGMENTS, type KindDescriptor, type LifecycleHooks } from './registry'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import { GRID_VIEW } from '../node/levelViews'
import { MEMORY_KIND, CHAPTER_KIND } from '../../features/memories/kinds'
import { fileNameSlug, slugify } from '../../shared/naming'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'

const Stub = defineComponent({ template: '<div />' })
const Other = defineComponent({ template: '<span />' })

describe('registry: kinds', () => {
  // FOLDER is a share root (core's own FolderShareDialog; ADR 0080 decision 6,
  // uc.sharing.share-public-viewer:folder-share-root).
  it('holds only the platform kind (FOLDER, shareable) until an application registers its own', () => {
    const r = createRegistry()
    expect(r.kind(FOLDER_KIND)).toMatchObject({ supported: true, icon: 'folder', shareable: true })
    expect(r.kind(MEMORY_KIND)).toEqual({ supported: false, kind: MEMORY_KIND })
  })

  it('is write-once: registering the same kind twice throws and keeps the first descriptor', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'images', shareable: true })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: false })).toThrow(/already registered/)
    expect(r.kind(MEMORY_KIND)).toMatchObject({ supported: true, icon: 'images', shareable: true })
  })

  it('refuses an application re-registering the platform FOLDER', () => {
    const r = createRegistry()
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: FOLDER_KIND, icon: 'file', shareable: false })).toThrow(/already registered/)
    expect(r.kind(FOLDER_KIND)).toMatchObject({ icon: 'folder', shareable: true })
  })

  it('resolves an unregistered kind to the explicit unsupported result, never undefined', () => {
    const r = createRegistry()
    const got = r.kind(CHAPTER_KIND)
    expect(got).toBeDefined()
    expect(got.supported).toBe(false)
    // A kind of an application this build does not carry is just as explicit.
    expect(createRegistry().kind('FUTURE' as NodeKind)).toEqual({ supported: false, kind: 'FUTURE' })
  })

  // The kind is an open token, so no compiler lists the kinds any more: this answer is what keeps a kind nobody
  // registered from rendering as anything but the explicit unsupported state (cr-platform-contract-split slice 4).
  it('answers by the registered token only: an unregistered kind is unsupported, whatever its spelling', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'EXPENSE', icon: 'tag', shareable: false })
    expect(r.kind('EXPENSE')).toMatchObject({ supported: true, kind: 'EXPENSE', icon: 'tag' })
    for (const unknown of ['INVOICE', 'expense', 'EXPENSES', '']) {
      expect(r.kind(unknown)).toEqual({ supported: false, kind: unknown })
    }
    // Control: the platform's own kind is there without any registration.
    expect(r.kind(FOLDER_KIND)).toMatchObject({ supported: true, kind: 'FOLDER' })
  })

  it('copies the descriptor: mutating the caller object afterwards does not change the registration', () => {
    const r = createRegistry()
    const d: KindDescriptor = { kind: MEMORY_KIND, icon: 'images', shareable: true, rekeyContent: nothingOfItsOwnToRekey }
    r.registerKind(d)
    d.shareable = false
    expect(r.kind(MEMORY_KIND)).toMatchObject({ shareable: true })
  })
})

// The slug hook (inbox #350): every slug token is derived through `slugFor`, so its default and its per-kind answer are
// what decides which names collide.
describe('registry: slugFor', () => {
  it('folds with slugify for a kind that registers no hook, the platform FOLDER included', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'images', shareable: true })
    expect(r.slugFor(FOLDER_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG'))
    expect(r.slugFor(MEMORY_KIND, 'Day1.md')).toBe('day1-md')
  })

  it('folds with slugify for a kind nobody registered (a token is still computable for it)', () => {
    expect(createRegistry().slugFor('FUTURE', 'Day1.md')).toBe('day1-md')
  })

  it('folds with the kind\'s own hook when it registers one, and only for that kind', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', shareable: false, slug: fileNameSlug })
    expect(r.slugFor('LEAF', 'Day1.md')).toBe('day1.md')
    expect(r.slugFor('LEAF', 'Foto.JPG')).toBe(r.slugFor('LEAF', 'foto.jpg'))
    expect(r.slugFor(FOLDER_KIND, 'Day1.md')).toBe('day1-md')
    expect(r.kind('LEAF')).toMatchObject({ supported: true, slug: fileNameSlug })
  })

  it('keeps the first hook: a second registration of the kind throws and does not swap the folding', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', shareable: false, slug: fileNameSlug })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', shareable: false, slug: () => 'x' })).toThrow(/already registered/)
    expect(r.slugFor('LEAF', 'A.B')).toBe('a.b')
  })
})

// cr-files-application P4c: a kind brings its create and share dialogs with its descriptor; no single-claim slot is left.
describe('registry: dialogs per kind', () => {
  it("pre-registers core's FolderShareDialog as FOLDER's share dialog, and FOLDER has no create dialog", async () => {
    const folder = createRegistry().kind(FOLDER_KIND)
    expect(folder.supported).toBe(true)
    if (!folder.supported) return
    expect(folder.createDialog).toBeUndefined() // a folder's create is core's FolderNameDialog, mounted by the shell
    // Lazy (the dialog's module graph reaches the registry again): resolve the async wrapper to the real component.
    const loader = (folder.shareDialog as { __asyncLoader?: () => Promise<unknown> } | undefined)?.__asyncLoader
    expect(loader, 'an async component wrapping the folder dialog').toBeTypeOf('function')
    const FolderShareDialog = (await import('../sharing/FolderShareDialog.vue')).default
    expect(await loader!()).toBe(FolderShareDialog)
    // The import transforms the dialog's whole graph (the date field among it) cold in this file: ~2 s alone,
    // past the 5 s default in a loaded full run (measured 2026-10-08, three reds in a row, green at 60 s — slow, not hung).
  }, 30_000)

  it('hands each kind its own createDialog and shareDialog; one component may serve several kinds', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: true, createDialog: Stub, shareDialog: Other })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', shareable: true, createDialog: Stub, shareDialog: Other })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', shareable: false })
    for (const k of [MEMORY_KIND, CHAPTER_KIND]) expect(r.kind(k)).toMatchObject({ createDialog: Stub, shareDialog: Other })
    const leaf = r.kind('LEAF')
    expect(leaf.supported && [leaf.createDialog, leaf.shareDialog]).toEqual([undefined, undefined])
    expect(r.kind('NOBODY')).toEqual({ supported: false, kind: 'NOBODY' }) // no dialog to find for an unregistered kind
  })

  it("an application cannot replace FOLDER's share dialog (FOLDER is write-once like every kind)", () => {
    const r = createRegistry()
    const before = r.kind(FOLDER_KIND)
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: FOLDER_KIND, icon: 'file', shareable: true, shareDialog: Stub })).toThrow(/already registered/)
    expect(r.kind(FOLDER_KIND)).toEqual(before)
  })

  it('has no single-claim slot left', () => {
    const r = createRegistry()
    // @ts-expect-error the per-kind descriptor fields replaced the single-claim slots in P4c
    expect(r.claimSlot).toBeUndefined()
    // @ts-expect-error see above
    expect(r.slot).toBeUndefined()
  })

  it('two registries do not share state', () => {
    const a = createRegistry()
    const b = createRegistry()
    a.addSlotItem('topbarTray', 'a.tray', Stub)
    a.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: true, shareDialog: Stub })
    expect(b.slotItems('topbarTray')).toEqual([])
    expect(b.kind(MEMORY_KIND).supported).toBe(false)
  })
})

describe('registry: list slots (topbarTray, sidebarPins)', () => {
  const Third = defineComponent({ template: '<p />' })

  it('is empty until an item is added: slotItems() is an empty list', () => {
    const r = createRegistry()
    expect(r.slotItems('topbarTray')).toEqual([])
    expect(r.slotItems('sidebarPins')).toEqual([])
  })

  it('keeps registration order when no order is given', () => {
    const r = createRegistry()
    r.addSlotItem('topbarTray', 'a.tray', Stub)
    r.addSlotItem('topbarTray', 'b.tray', Other)
    expect(r.slotItems('topbarTray')).toEqual([
      { id: 'a.tray', component: Stub },
      { id: 'b.tray', component: Other },
    ])
  })

  it('sorts by order, ascending; a tie keeps registration order', () => {
    const r = createRegistry()
    r.addSlotItem('sidebarPins', 'late', Stub, 20)
    r.addSlotItem('sidebarPins', 'tie-first', Other, 10)
    r.addSlotItem('sidebarPins', 'tie-second', Third, 10)
    expect(r.slotItems('sidebarPins').map((i) => i.id)).toEqual(['tie-first', 'tie-second', 'late'])
  })

  it('is write-once per (slot, id): a duplicate id throws, naming slot and id, and the first item stays', () => {
    const r = createRegistry()
    r.addSlotItem('topbarTray', 'memories.upload-status', Stub)
    expect(() => r.addSlotItem('topbarTray', 'memories.upload-status', Other)).toThrow(
      /topbarTray.*memories\.upload-status/,
    )
    expect(r.slotItems('topbarTray')).toEqual([{ id: 'memories.upload-status', component: Stub }])
  })

  it('scopes the id to its list: the same id in the other list is fine', () => {
    const r = createRegistry()
    r.addSlotItem('topbarTray', 'x', Stub)
    expect(() => r.addSlotItem('sidebarPins', 'x', Other)).not.toThrow()
    expect(r.slotItems('sidebarPins')).toEqual([{ id: 'x', component: Other }])
  })

  it('hands out a copy: mutating the returned list does not change the slot', () => {
    const r = createRegistry()
    r.addSlotItem('topbarTray', 'a', Stub)
    ;(r.slotItems('topbarTray') as { id: string; component: unknown }[]).push({ id: 'b', component: Other })
    expect(r.slotItems('topbarTray')).toHaveLength(1)
  })

  it('refuses addSlotItem on a name that is no list slot (a JS caller gets an error, not a silent add)', () => {
    const r = createRegistry()
    // The type refuses it (the `@ts-expect-error` turns `npm run typecheck` red if the type widens again); the runtime
    // guard is for a caller the type checker does not see.
    // @ts-expect-error the former single-claim slot is no list slot
    expect(() => r.addSlotItem('shareDialog', 'a', Stub)).toThrow(/shareDialog is not a list slot/)
    expect(r.slotItems('topbarTray')).toEqual([])
  })
})

describe('registry: drop pages (share bodies)', () => {
  it('is empty until claimed: shareBody() is null, not undefined', () => {
    const r = createRegistry()
    expect(r.shareBody(MEMORY_KIND)).toBeNull()
  })

  it('is single-claim per key: a second claim throws and the first body stays', () => {
    const r = createRegistry()
    r.claimShareBody(MEMORY_KIND, Stub)
    expect(() => r.claimShareBody(MEMORY_KIND, Other)).toThrow(/already claimed/)
    expect(r.shareBody(MEMORY_KIND)).toBe(Stub)
  })

  it('answers by the claimed kind token only: a root kind nobody claimed has no body', () => {
    const r = createRegistry()
    r.claimShareBody('EXPENSE', Stub)
    expect(r.shareBody('EXPENSE')).toBe(Stub)
    for (const unclaimed of [MEMORY_KIND, 'expense', 'EXPENSES', 'drop', '']) expect(r.shareBody(unclaimed)).toBeNull()
    // Control: claiming one kind leaves another free to claim.
    expect(() => r.claimShareBody(MEMORY_KIND, Other)).not.toThrow()
    expect(r.shareBody(MEMORY_KIND)).toBe(Other)
  })
  // A WRITE_ONLY link's drop page is an application's (inbox #401 S5); no such link roots at a folder (ADR 0080 decision
  // 11), so FOLDER is claimed by nobody — an application's claim of it throws and leaves it unclaimed.
  it('refuses a claim of FOLDER: no drop page for the platform kind', () => {
    const r = createRegistry()
    expect(r.shareBody(FOLDER_KIND)).toBeNull()
    expect(() => r.claimShareBody(FOLDER_KIND, Stub)).toThrow(/already claimed/)
    expect(r.shareBody(FOLDER_KIND)).toBeNull()
  })
})

describe('registry: lifecycle hooks', () => {
  const hooks = (over: Partial<LifecycleHooks> = {}): LifecycleHooks => ({ onMasterKeyRotated: async () => {}, ...over })

  it('is write-once per application id: a second registration throws and the first hooks stay', () => {
    const r = createRegistry()
    const first = vi.fn()
    r.registerLifecycle('memories', hooks({ onScopeReset: first }))
    expect(() => r.registerLifecycle('memories', hooks({ onScopeReset: vi.fn() }))).toThrow(/already registered/)
    r.scopeReset()
    expect(first).toHaveBeenCalledTimes(1)
  })

  it('refuses a hook set without onMasterKeyRotated (it is required, not optional)', () => {
    const r = createRegistry()
    expect(() => r.registerLifecycle('x', {} as LifecycleHooks)).toThrow(/onMasterKeyRotated/)
    // The refused registration left nothing behind: the rotation gate is still closed.
    expect(() => r.assertRotationHooks()).toThrow(/refusing to rotate/)
  })

  it('FAILS CLOSED: assertRotationHooks throws on an empty registry and passes once an application registered', () => {
    const r = createRegistry()
    expect(() => r.assertRotationHooks()).toThrow(/refusing to rotate/) // the negative…
    r.registerLifecycle('memories', hooks())
    expect(() => r.assertRotationHooks()).not.toThrow() // …and its live control
  })

  it('passes the arguments to each hook and runs every application', async () => {
    const r = createRegistry()
    const a = vi.fn(async () => {})
    const b = vi.fn(async () => {})
    r.registerLifecycle('a', hooks({ onMasterKeyRotated: a }))
    r.registerLifecycle('b', hooks({ onMasterKeyRotated: b }))
    const o = new Uint8Array([1])
    const n = new Uint8Array([2])
    await r.masterKeyRotated(o, n, ['s1'])
    expect(a).toHaveBeenCalledWith(o, n, ['s1'])
    expect(b).toHaveBeenCalledWith(o, n, ['s1'])
  })

  it('a failing hook does not stop the next application, and its error is rethrown afterwards', async () => {
    const r = createRegistry()
    const second = vi.fn()
    r.registerLifecycle('a', hooks({ onScopeEntered: () => Promise.reject(new Error('boom')) }))
    r.registerLifecycle('b', hooks({ onScopeEntered: second }))
    await expect(r.scopeEntered('s1')).rejects.toThrow('boom')
    expect(second).toHaveBeenCalledWith('s1')
  })

  it('optional hooks may be absent: scopeReset / scopeEntered are no-ops for an application that sets none', async () => {
    const r = createRegistry()
    r.registerLifecycle('a', hooks())
    expect(() => r.scopeReset()).not.toThrow()
    await expect(r.scopeEntered('s1')).resolves.toBeUndefined()
  })
})

// Inbox #328: an application claims its URL route slug once (ADR 0015); Depot declares it has none.
describe('registry: applications (uc.platform.switch-application-off)', () => {
  it('names each application once with its kinds; a kind answers its application, a folder and a stranger none', () => {
    const r = createRegistry()
    r.registerApplication({ id: 'memories', name: 'memory.memories', kinds: [MEMORY_KIND, CHAPTER_KIND] })
    r.registerApplication({ id: 'depot', name: 'depot.title', kinds: ['FILE', 'DEPOT_DROPBOX'] })
    expect(r.applications()).toEqual([{ id: 'memories', name: 'memory.memories' }, { id: 'depot', name: 'depot.title' }])
    expect(r.applicationOf(CHAPTER_KIND)).toBe('memories')
    expect(r.applicationOf('DEPOT_DROPBOX')).toBe('depot')
    expect(r.applicationOf(FOLDER_KIND)).toBeNull()
    expect(r.applicationOf('NOBODY')).toBeNull()
  })

  it('refuses an id twice, a kind twice, FOLDER and no kind at all — and keeps nothing of a refused one', () => {
    const r = createRegistry()
    r.registerApplication({ id: 'memories', name: 'memory.memories', kinds: [MEMORY_KIND] })
    expect(() => r.registerApplication({ id: 'memories', name: 'x', kinds: ['OTHER'] })).toThrow(/already registered/)
    expect(() => r.registerApplication({ id: 'other', name: 'x', kinds: ['OTHER', MEMORY_KIND] })).toThrow(/already belongs to memories/)
    expect(() => r.registerApplication({ id: 'other', name: 'x', kinds: [FOLDER_KIND] })).toThrow(/platform/)
    expect(() => r.registerApplication({ id: 'other', name: 'x', kinds: [] })).toThrow(/names no node kind/)
    expect(r.applicationOf('OTHER')).toBeNull()
    expect(r.applications().map((a) => a.id)).toEqual(['memories'])
  })
})

describe('registry: route slugs', () => {
  it('maps the claimed kinds to their application\'s slug and group, a folder and the rest to none', () => {
    const r = createRegistry()
    r.claimRouteSlug('memories', [MEMORY_KIND, CHAPTER_KIND])
    r.claimNoRouteSegment(['FILE', 'DEPOT_DROPBOX'])
    expect(r.routeOf(MEMORY_KIND)).toEqual({ slug: 'memories', group: 'application:memories' })
    expect(r.routeOf(CHAPTER_KIND)).toEqual({ slug: 'memories', group: 'application:memories' })
    expect(r.routeOf('FILE')).toEqual({ slug: null, group: 'none' })
    expect(r.routeOf('DEPOT_DROPBOX')).toEqual(r.routeOf('FILE'))
    expect(r.routeOf(FOLDER_KIND)).toEqual({ slug: null, group: 'folder' })
    // a kind nobody claimed has no segment and numbers among the plain names of the segment-less application
    expect(r.routeOf('FUTURE')).toEqual({ slug: null, group: 'none' })
    expect(r.routeSlugs()).toEqual(['memories'])
  })

  it('is write-once: a second claim of a slug throws and keeps the first', () => {
    const r = createRegistry()
    r.claimRouteSlug('memories', [MEMORY_KIND])
    expect(() => r.claimRouteSlug('memories', ['OTHER'])).toThrow(/already claimed/)
    expect(r.routeOf('OTHER').slug).toBeNull()
    expect(r.routeOf(MEMORY_KIND).slug).toBe('memories')
  })

  it('refuses a kind claimed twice (also under another slug), FOLDER, and an empty claim — and registers nothing then', () => {
    const r = createRegistry()
    r.claimRouteSlug('memories', [MEMORY_KIND])
    expect(() => r.claimRouteSlug('other', [MEMORY_KIND])).toThrow(/already claimed/)
    expect(() => r.claimNoRouteSegment([MEMORY_KIND])).toThrow(/already claimed/)
    expect(() => r.claimRouteSlug('folders', [FOLDER_KIND])).toThrow(/platform/)
    expect(() => r.claimNoRouteSegment([FOLDER_KIND])).toThrow(/platform/)
    expect(() => r.claimRouteSlug('empty', [])).toThrow(/no node kind/)
    // an atomic claim: one bad kind among good ones claims none
    expect(() => r.claimRouteSlug('mixed', ['A', MEMORY_KIND])).toThrow()
    expect(r.routeOf('A').slug).toBeNull()
    expect(r.routeSlugs()).toEqual(['memories'])
    r.claimRouteSlug('mixed', ['A']) // the slug stayed free
  })

  it('lets one application declare it has no segment — a second one cannot share the plain names', () => {
    const r = createRegistry()
    r.claimNoRouteSegment(['FILE'])
    expect(() => r.claimNoRouteSegment(['INVOICE'])).toThrow(/no route segment/)
    expect(r.routeOf('INVOICE')).toEqual({ slug: null, group: 'none' }) // unclaimed, but only as any unknown kind is
  })

  it('refuses a slug that is no slug, and the words the platform keeps', () => {
    const r = createRegistry()
    for (const bad of ['', 'Memories', 'a b', '-a', 'a-', 'a--b', '1a', 'a/b', '~me', '@g', 'a%2Fb']) {
      expect(() => r.claimRouteSlug(bad, [`K${bad.length}`]), bad).toThrow(/not a slug/)
    }
    expect(RESERVED_ROUTE_SEGMENTS.length).toBeGreaterThan(0)
    for (const word of RESERVED_ROUTE_SEGMENTS) {
      expect(() => r.claimRouteSlug(word, ['K']), word).toThrow(/reserved/)
    }
    // the platform's history-mode routes are among them
    for (const word of ['login', 'settings', 'invitations', 's', 'api', 'people']) expect(RESERVED_ROUTE_SEGMENTS).toContain(word)
    expect(() => r.claimRouteSlug('people', ['PERSON']), 'the profile route (ADR 0083)').toThrow(/reserved/)
    expect(r.routeSlugs()).toEqual([])
  })
})

describe('registry: the level "New" menu (inbox #406)', () => {
  const allow = () => true
  const entry = (order: number, label = 'k.label') => ({ label, icon: 'plus' as const, order, allowed: allow })

  it('is empty until a kind brings an entry', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', shareable: false })
    expect(r.levelMenu()).toEqual([])
  })

  it('lists every create entry (id create:<KIND>) and level action by order, a tie in registration order', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'B', icon: 'file', shareable: false, createDialog: Stub, createEntry: entry(20, 'b.new') })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', shareable: false, createDialog: Stub, createEntry: entry(10, 'a.new'), levelActions: [{ id: 'a.up', ...entry(30, 'a.up') }] })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'C', icon: 'file', shareable: false, createDialog: Stub, createEntry: entry(20, 'c.new') })
    expect(r.levelMenu().map((e) => [e.id, e.create ?? null])).toEqual([['create:A', 'A'], ['create:B', 'B'], ['create:C', 'C'], ['a.up', null]])
  })

  it('refuses a create entry without a createDialog to open, and registers nothing', () => {
    const r = createRegistry()
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', shareable: false, createEntry: entry(1) })).toThrow(/no createDialog/)
    expect(r.kind('A')).toEqual({ supported: false, kind: 'A' })
  })

  it('is write-once for level action ids: a taken id, a duplicate within a kind and the reserved create: prefix throw', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', shareable: false, levelActions: [{ id: 'a.up', ...entry(1) }] })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'B', icon: 'file', shareable: false, levelActions: [{ id: 'a.up', ...entry(2) }] })).toThrow(/already registered/)
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'C', icon: 'file', shareable: false, levelActions: [{ id: 'c.1', ...entry(1) }, { id: 'c.1', ...entry(2) }] })).toThrow(/already registered/)
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'D', icon: 'file', shareable: false, levelActions: [{ id: 'create:FOLDER', ...entry(1) }] })).toThrow(/already registered/)
    expect(r.levelMenu().map((e) => e.id)).toEqual(['a.up'])
  })
})


// inbox #407: the unified level list - a kind names its row factory, its tab and its views; the registry derives the row
// sources and the tabs.
describe('registry: the unified level list', () => {
  const rowsA = () => ({ row: () => null })
  const rowsB = () => ({ row: () => null })
  const map = { mode: 'map', icon: 'map', label: 'x.map' } as const

  it('is empty until a kind names a row factory, and the sections and the level view slot are gone', () => {
    const r = createRegistry()
    expect(r.lists()).toEqual([])
    expect(r.tabs()).toEqual([])
    // @ts-expect-error the sections were retired with the unified list (inbox #407): rows replace them
    expect(r.sections).toBeUndefined()
    // @ts-expect-error the level view is core's since P4b, and `slot()` went with the last single-claim slot in P4c
    expect(r.slot).toBeUndefined()
  })

  it('a kind without a list adds none, and a refused re-registration does not add its list or its tab', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', shareable: false })
    expect(r.lists()).toEqual([])
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: true })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: true, list: { rows: rowsA, tabLabel: 'x.mem' } })).toThrow(/already registered/)
    expect(r.lists()).toEqual([])
    expect(r.tabs()).toEqual([])
  })

  it('hands out copies: mutating what lists() and tabs() returned changes nothing', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: true, list: { rows: rowsA, tabLabel: 'x.mem' } })
    const lists = r.lists() as unknown as { kinds: string[]; views: unknown[] }[]
    lists[0].kinds.push('FORGED')
    lists[0].views.push(GRID_VIEW)
    ;(lists as unknown[]).length = 0
    const tabs = r.tabs() as unknown as { kinds: string[] }[]
    tabs[0].kinds.push('FORGED')
    expect(r.lists()).toEqual([{ rows: rowsA, kinds: [MEMORY_KIND], views: [] }])
    expect(r.tabs().map((t) => t.kinds)).toEqual([[MEMORY_KIND]])
  })

  it('keys a row source by its function: two kinds naming one factory are ONE source carrying both, in registration order', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: true, list: { rows: rowsA, views: [GRID_VIEW, map] } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'OTHER', icon: 'file', shareable: false, list: { rows: rowsB } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', shareable: true, list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(r.lists()).toEqual([
      { rows: rowsA, kinds: [MEMORY_KIND, CHAPTER_KIND], views: [GRID_VIEW, map] },
      { rows: rowsB, kinds: ['OTHER'], views: [] },
    ])
  })

  it('refuses a kind naming a shared factory with other views, and registers nothing of it', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: true, list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', shareable: true, list: { rows: rowsA, views: [GRID_VIEW] } }))
      .toThrow(/CHAPTER names the row source of MEMORY with views \[grid\], not \[grid, map\]/)
    expect(r.kind(CHAPTER_KIND)).toEqual({ supported: false, kind: CHAPTER_KIND })
    expect(r.lists()).toHaveLength(1)
    // the refused kind added no tab either
    expect(r.tabs()).toEqual([])
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', shareable: true, list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(r.lists()).toEqual([{ rows: rowsA, kinds: [MEMORY_KIND, CHAPTER_KIND], views: [GRID_VIEW, map] }])
  })

  it('derives one tab per distinct label, in registration order: kinds sharing a label share a tab, the id is its first kind', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareable: true, list: { rows: rowsA, tabLabel: 'x.mem' } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'FILE', icon: 'file', shareable: true, list: { rows: rowsB, tabLabel: 'x.files' } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', shareable: true, list: { rows: rowsA, tabLabel: 'x.mem' } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'NOTAB', icon: 'file', shareable: false, list: { rows: rowsB } })
    expect(r.tabs().map((t) => ({ id: t.id, label: t.label, kinds: t.kinds }))).toEqual([
      { id: MEMORY_KIND, label: 'x.mem', kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { id: 'FILE', label: 'x.files', kinds: ['FILE'] },
    ])
  })

  it('gives every menu entry the kind that declared it', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey,
      kind: 'FILE', icon: 'file', shareable: true,
      levelActions: [{ id: 'f.up', label: 'x', icon: 'file', order: 1, allowed: () => true, acceptsDrop: true }],
    })
    expect(r.levelMenu().map((e) => ({ id: e.id, kind: e.kind, acceptsDrop: e.acceptsDrop }))).toEqual([{ id: 'f.up', kind: 'FILE', acceptsDrop: true }])
  })
})

describe('registry: person-profile sections (ADR 0083)', () => {
  it('lists the claimed sections in order and refuses the same component twice', () => {
    const r = createRegistry()
    const A = { name: 'A' }
    const B = { name: 'B' }
    expect(r.personSections()).toEqual([])
    r.claimPersonSection(A)
    r.claimPersonSection(B)
    expect(r.personSections()).toEqual([A, B])
    expect(() => r.claimPersonSection(A)).toThrow(/already claimed/)
  })
})
