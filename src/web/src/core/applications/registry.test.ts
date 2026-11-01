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
  it('holds only the platform kind (FOLDER, shareable by its dialog) until an application registers its own', () => {
    const r = createRegistry()
    expect(r.kind(FOLDER_KIND)).toMatchObject({ supported: true, icon: 'folder' })
    expect(r.kind(MEMORY_KIND)).toEqual({ supported: false, kind: MEMORY_KIND })
  })

  it('is write-once: registering the same kind twice throws and keeps the first descriptor', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'images' })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file' })).toThrow(/already registered/)
    expect(r.kind(MEMORY_KIND)).toMatchObject({ supported: true, icon: 'images' })
  })

  it('refuses an application re-registering the platform FOLDER', () => {
    const r = createRegistry()
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: FOLDER_KIND, icon: 'file' })).toThrow(/already registered/)
    expect(r.kind(FOLDER_KIND)).toMatchObject({ icon: 'folder' })
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
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'EXPENSE', icon: 'tag' })
    expect(r.kind('EXPENSE')).toMatchObject({ supported: true, kind: 'EXPENSE', icon: 'tag' })
    for (const unknown of ['INVOICE', 'expense', 'EXPENSES', '']) {
      expect(r.kind(unknown)).toEqual({ supported: false, kind: unknown })
    }
    // Control: the platform's own kind is there without any registration.
    expect(r.kind(FOLDER_KIND)).toMatchObject({ supported: true, kind: 'FOLDER' })
  })

  it('copies the descriptor: mutating the caller object afterwards does not change the registration', () => {
    const r = createRegistry()
    const d: KindDescriptor = { kind: MEMORY_KIND, icon: 'images', rekeyContent: nothingOfItsOwnToRekey }
    r.registerKind(d)
    d.icon = 'file'
    expect(r.kind(MEMORY_KIND)).toMatchObject({ icon: 'images' })
  })
})

// The slug hook (inbox #350): every slug token is derived through `tokenInputFor` from the kind's folding, so its default
// and its per-kind answer are what decides which names collide (no kind here claims a route: the input is the bare slug).
describe('registry: the kind\'s slug folding', () => {
  it('folds with slugify for a kind that registers no hook, the platform FOLDER included', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'images' })
    expect(r.tokenInputFor(FOLDER_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG'))
    expect(r.tokenInputFor(MEMORY_KIND, 'Day1.md')).toBe('day1-md')
  })

  it('folds with slugify for a kind nobody registered (a token is still computable for it)', () => {
    expect(createRegistry().tokenInputFor('FUTURE', 'Day1.md')).toBe('day1-md')
  })

  it('folds with the kind\'s own hook when it registers one, and only for that kind', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: fileNameSlug })
    expect(r.tokenInputFor('LEAF', 'Day1.md')).toBe('day1.md')
    expect(r.tokenInputFor('LEAF', 'Foto.JPG')).toBe(r.tokenInputFor('LEAF', 'foto.jpg'))
    expect(r.tokenInputFor(FOLDER_KIND, 'Day1.md')).toBe('day1-md')
    expect(r.kind('LEAF')).toMatchObject({ supported: true, slug: fileNameSlug })
  })

  it('keeps the first hook: a second registration of the kind throws and does not swap the folding', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: fileNameSlug })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: () => 'x' })).toThrow(/already registered/)
    expect(r.tokenInputFor('LEAF', 'A.B')).toBe('a.b')
  })
})

// ADR 0080 decision 16: the slug token's input carries the route group, so a name is unique among the
// siblings of its group only — each application with a route slug is one, the plain names (folders, the application with
// no segment, a kind nobody claimed) another.
describe('registry: tokenInputFor', () => {
  function routed() {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'images' })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'POT', icon: 'file' })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: fileNameSlug })
    r.claimRouteSlug('memories', [MEMORY_KIND, CHAPTER_KIND])
    r.claimRouteSlug('expenses', ['POT'])
    r.claimNoRouteSegment(['LEAF'])
    return r
  }

  it('puts a claimed route slug and a slash before the kind\'s slug', () => {
    const r = routed()
    expect(r.tokenInputFor(MEMORY_KIND, 'Korfubanien')).toBe('memories/korfubanien')
    expect(r.tokenInputFor(CHAPTER_KIND, 'Tag 1')).toBe('memories/tag-1')
    expect(r.tokenInputFor('POT', 'Korfubanien')).toBe('expenses/korfubanien')
  })

  it('keeps the bare slug for a folder, the application with no segment and a kind nobody claimed', () => {
    const r = routed()
    expect(r.tokenInputFor(FOLDER_KIND, 'Korfubanien')).toBe('korfubanien')
    expect(r.tokenInputFor('LEAF', 'Korfubanien')).toBe('korfubanien')
    expect(r.tokenInputFor('FUTURE', 'Korfubanien')).toBe('korfubanien')
  })

  it('so two applications\' nodes of one name differ, and a folder and a plain-named file of one name do not', () => {
    const r = routed()
    expect(r.tokenInputFor(MEMORY_KIND, 'Rom')).not.toBe(r.tokenInputFor('POT', 'Rom'))
    expect(r.tokenInputFor(MEMORY_KIND, 'Rom')).not.toBe(r.tokenInputFor(FOLDER_KIND, 'Rom'))
    expect(r.tokenInputFor(MEMORY_KIND, 'Rom')).toBe(r.tokenInputFor(CHAPTER_KIND, 'rom'))
    expect(r.tokenInputFor('LEAF', 'rom')).toBe(r.tokenInputFor(FOLDER_KIND, 'Rom'))
  })

  it('takes the folding from the kind\'s hook, the prefix from its claim', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: fileNameSlug })
    r.claimRouteSlug('leaves', ['LEAF'])
    expect(r.tokenInputFor('LEAF', 'Day1.md')).toBe('leaves/day1.md')
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
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', createDialog: Stub, shareDialog: Other })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', createDialog: Stub, shareDialog: Other })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file' })
    for (const k of [MEMORY_KIND, CHAPTER_KIND]) expect(r.kind(k)).toMatchObject({ createDialog: Stub, shareDialog: Other })
    const leaf = r.kind('LEAF')
    expect(leaf.supported && [leaf.createDialog, leaf.shareDialog]).toEqual([undefined, undefined])
    expect(r.kind('NOBODY')).toEqual({ supported: false, kind: 'NOBODY' }) // no dialog to find for an unregistered kind
  })

  it("an application cannot replace FOLDER's share dialog (FOLDER is write-once like every kind)", () => {
    const r = createRegistry()
    const before = r.kind(FOLDER_KIND)
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: FOLDER_KIND, icon: 'file', shareDialog: Stub })).toThrow(/already registered/)
    expect(r.kind(FOLDER_KIND)).toEqual(before)
  })

  it('has no shell slot: the top bar and the sidebar are core\'s alone', () => {
    const r = createRegistry()
    // @ts-expect-error the per-kind descriptor fields replaced the single-claim slots in P4c
    expect(r.claimSlot).toBeUndefined()
    // @ts-expect-error see above
    expect(r.slot).toBeUndefined()
    // @ts-expect-error the list slots had no user and are gone (the Favorites and the upload status are core's)
    expect(r.addSlotItem).toBeUndefined()
    // @ts-expect-error see above
    expect(r.slotItems).toBeUndefined()
  })

  it('two registries do not share state', () => {
    const a = createRegistry()
    const b = createRegistry()
    a.registerApplication({ id: 'a', name: 'a.name', kinds: [MEMORY_KIND] })
    a.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', shareDialog: Stub })
    expect(b.applications()).toEqual([])
    expect(b.kind(MEMORY_KIND).supported).toBe(false)
  })

  it('lists every registered kind, FOLDER first, then in registration order (a copy)', () => {
    const r = createRegistry()
    expect(r.kinds()).toEqual([FOLDER_KIND])
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file' })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file' })
    expect(r.kinds()).toEqual([FOLDER_KIND, MEMORY_KIND, 'LEAF'])
    ;(r.kinds() as string[]).push('FORGED')
    expect(r.kinds()).toEqual([FOLDER_KIND, MEMORY_KIND, 'LEAF'])
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
  /** A registry that named the applications `ids` (one kind each), as a composition root does before their hooks. */
  const named = (...ids: string[]) => {
    const r = createRegistry()
    for (const id of ids) r.registerApplication({ id, name: `${id}.name`, kinds: [`K_${id.toUpperCase()}`] })
    return r
  }

  it('is write-once per application id: a second registration throws and the first hooks stay', () => {
    const r = named('memories')
    const first = vi.fn()
    r.registerLifecycle('memories', hooks({ onScopeReset: first }))
    expect(() => r.registerLifecycle('memories', hooks({ onScopeReset: vi.fn() }))).toThrow(/already registered/)
    r.scopeReset()
    expect(first).toHaveBeenCalledTimes(1)
  })

  it('refuses the hooks of an application nobody named, and keeps nothing of them', async () => {
    const r = named('memories')
    const rotated = vi.fn(async () => {})
    expect(() => r.registerLifecycle('memoirs', hooks({ onMasterKeyRotated: rotated }))).toThrow(/no application memoirs is registered/)
    expect(() => createRegistry().registerLifecycle('memories', hooks())).toThrow(/registerApplication first/)
    await r.masterKeyRotated(new Uint8Array([1]), new Uint8Array([2]), [])
    expect(rotated).not.toHaveBeenCalled()
    r.registerLifecycle('memories', hooks()) // control: the named id is taken
  })

  it('refuses a hook set without onMasterKeyRotated (it is required, not optional)', () => {
    const r = named('x')
    expect(() => r.registerLifecycle('x', {} as LifecycleHooks)).toThrow(/onMasterKeyRotated/)
    r.registerLifecycle('x', hooks()) // the refused registration left nothing behind: the id is still free
  })

  // There is no registry-wide rotation gate (see the registry's header): core re-seals its own Master-Key material itself,
  // so a build with no application rotates; what an application seals is its own required hook's.
  it('a core-only registry rotates: masterKeyRotated runs no hook and resolves', async () => {
    const r = createRegistry()
    // @ts-expect-error the gate is gone: a build without an application's hooks must be able to rotate keys
    expect(r.assertRotationHooks).toBeUndefined()
    await expect(r.masterKeyRotated(new Uint8Array([1]), new Uint8Array([2]), ['s1'])).resolves.toBeUndefined()
  })

  it('passes the arguments to each hook and runs every application', async () => {
    const r = named('a', 'b')
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
    const r = named('a', 'b')
    const second = vi.fn()
    r.registerLifecycle('a', hooks({ onScopeEntered: () => Promise.reject(new Error('boom')) }))
    r.registerLifecycle('b', hooks({ onScopeEntered: second }))
    await expect(r.scopeEntered('s1')).rejects.toThrow('boom')
    expect(second).toHaveBeenCalledWith('s1')
  })

  it('optional hooks may be absent: scopeReset / scopeEntered are no-ops for an application that sets none', async () => {
    const r = named('a')
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

  it('tells a kind whose route was decided (a slug, or no segment) from one nobody claimed, which routes alike', () => {
    const r = createRegistry()
    r.claimRouteSlug('memories', [MEMORY_KIND])
    r.claimNoRouteSegment(['FILE'])
    expect([MEMORY_KIND, 'FILE'].map((k) => r.routeDecided(k))).toEqual([true, true])
    expect(r.routeOf('INVOICE')).toEqual(r.routeOf('FILE')) // the premise: an unclaimed kind shares the plain names…
    expect(r.routeDecided('INVOICE')).toBe(false) // …and yet decided nothing
    expect(r.routeDecided(FOLDER_KIND)).toBe(false) // the platform's, claimed by no application
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
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file' })
    expect(r.levelMenu()).toEqual([])
  })

  it('lists every create entry (id create:<KIND>) and level action by order, a tie in registration order', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'B', icon: 'file', createDialog: Stub, createEntry: entry(20, 'b.new') })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', createDialog: Stub, createEntry: entry(10, 'a.new'), levelActions: [{ id: 'a.up', ...entry(30, 'a.up') }] })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'C', icon: 'file', createDialog: Stub, createEntry: entry(20, 'c.new') })
    expect(r.levelMenu().map((e) => [e.id, e.create ?? null])).toEqual([['create:A', 'A'], ['create:B', 'B'], ['create:C', 'C'], ['a.up', null]])
  })

  it('refuses a create entry without a createDialog to open, and registers nothing', () => {
    const r = createRegistry()
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', createEntry: entry(1) })).toThrow(/no createDialog/)
    expect(r.kind('A')).toEqual({ supported: false, kind: 'A' })
  })

  it('is write-once for level action ids: a taken id, a duplicate within a kind and the reserved create: prefix throw', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', levelActions: [{ id: 'a.up', ...entry(1) }] })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'B', icon: 'file', levelActions: [{ id: 'a.up', ...entry(2) }] })).toThrow(/already registered/)
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'C', icon: 'file', levelActions: [{ id: 'c.1', ...entry(1) }, { id: 'c.1', ...entry(2) }] })).toThrow(/already registered/)
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'D', icon: 'file', levelActions: [{ id: 'create:FOLDER', ...entry(1) }] })).toThrow(/already registered/)
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
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file' })
    expect(r.lists()).toEqual([])
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file' })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, tabLabel: 'x.mem' } })).toThrow(/already registered/)
    expect(r.lists()).toEqual([])
    expect(r.tabs()).toEqual([])
  })

  it('hands out copies: mutating what lists() and tabs() returned changes nothing', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, tabLabel: 'x.mem' } })
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
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW, map] } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'OTHER', icon: 'file', list: { rows: rowsB } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(r.lists()).toEqual([
      { rows: rowsA, kinds: [MEMORY_KIND, CHAPTER_KIND], views: [GRID_VIEW, map] },
      { rows: rowsB, kinds: ['OTHER'], views: [] },
    ])
  })

  it('refuses a kind naming a shared factory with other views, and registers nothing of it', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(() => r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW] } }))
      .toThrow(/CHAPTER names the row source of MEMORY with views \[grid\], not \[grid, map\]/)
    expect(r.kind(CHAPTER_KIND)).toEqual({ supported: false, kind: CHAPTER_KIND })
    expect(r.lists()).toHaveLength(1)
    // the refused kind added no tab either
    expect(r.tabs()).toEqual([])
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(r.lists()).toEqual([{ rows: rowsA, kinds: [MEMORY_KIND, CHAPTER_KIND], views: [GRID_VIEW, map] }])
  })

  it('derives one tab per distinct label, in registration order: kinds sharing a label share a tab, the id is its first kind', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, tabLabel: 'x.mem' } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'FILE', icon: 'file', list: { rows: rowsB, tabLabel: 'x.files' } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', list: { rows: rowsA, tabLabel: 'x.mem' } })
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'NOTAB', icon: 'file', list: { rows: rowsB } })
    expect(r.tabs().map((t) => ({ id: t.id, label: t.label, kinds: t.kinds }))).toEqual([
      { id: MEMORY_KIND, label: 'x.mem', kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { id: 'FILE', label: 'x.files', kinds: ['FILE'] },
    ])
  })

  it('gives every menu entry the kind that declared it', () => {
    const r = createRegistry()
    r.registerKind({ rekeyContent: nothingOfItsOwnToRekey,
      kind: 'FILE', icon: 'file', 
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
