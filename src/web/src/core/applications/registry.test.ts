import { describe, it, expect, vi } from 'vitest'
import { defineComponent } from 'vue'
import { createRegistry, RESERVED_ROUTE_SEGMENTS, type KindRegistration, type LifecycleHooks } from './registry'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import { GRID_VIEW } from '../node/levelViews'
import { MEMORY_KIND, CHAPTER_KIND } from '../../features/memories/kinds'
import { fileNameSlug, slugify } from '../../shared/naming'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import { testApp, testKind } from '../../test/testApplication'

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
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'images' })
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file' })).toThrow(/already registered/)
    expect(r.kind(MEMORY_KIND)).toMatchObject({ supported: true, icon: 'images' })
  })

  it('refuses an application re-registering the platform FOLDER', () => {
    const r = createRegistry()
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: FOLDER_KIND, icon: 'file' })).toThrow(/already registered/)
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
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'EXPENSE', icon: 'tag' })
    expect(r.kind('EXPENSE')).toMatchObject({ supported: true, kind: 'EXPENSE', icon: 'tag' })
    for (const unknown of ['INVOICE', 'expense', 'EXPENSES', '']) {
      expect(r.kind(unknown)).toEqual({ supported: false, kind: unknown })
    }
    // Control: the platform's own kind is there without any registration.
    expect(r.kind(FOLDER_KIND)).toMatchObject({ supported: true, kind: 'FOLDER' })
  })

  it('copies the descriptor: mutating the caller object afterwards does not change the registration', () => {
    const r = createRegistry()
    const d: KindRegistration = { icon: 'images', rekeyContent: nothingOfItsOwnToRekey }
    testApp(r).kind(MEMORY_KIND, d)
    d.icon = 'file'
    expect(r.kind(MEMORY_KIND)).toMatchObject({ icon: 'images' })
  })
})

// The slug hook (inbox #350): every slug token is derived through `tokenInputFor` from the kind's folding, so its default
// and its per-kind answer are what decides which names collide (no kind here claims a route: the input is the bare slug).
describe('registry: the kind\'s slug folding', () => {
  it('folds with slugify for a kind that registers no hook, the platform FOLDER included', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'images' })
    expect(r.tokenInputFor(FOLDER_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG'))
    expect(r.tokenInputFor(MEMORY_KIND, 'Day1.md')).toBe('day1-md')
  })

  it('folds with slugify for a kind nobody registered (a token is still computable for it)', () => {
    expect(createRegistry().tokenInputFor('FUTURE', 'Day1.md')).toBe('day1-md')
  })

  it('folds with the kind\'s own hook when it registers one, and only for that kind', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: fileNameSlug })
    expect(r.tokenInputFor('LEAF', 'Day1.md')).toBe('day1.md')
    expect(r.tokenInputFor('LEAF', 'Foto.JPG')).toBe(r.tokenInputFor('LEAF', 'foto.jpg'))
    expect(r.tokenInputFor(FOLDER_KIND, 'Day1.md')).toBe('day1-md')
    expect(r.kind('LEAF')).toMatchObject({ supported: true, slug: fileNameSlug })
  })

  it('keeps the first hook: a second registration of the kind throws and does not swap the folding', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: fileNameSlug })
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: () => 'x' })).toThrow(/already registered/)
    expect(r.tokenInputFor('LEAF', 'A.B')).toBe('a.b')
  })
})

// ADR 0080 decision 16: the slug token's input carries the route group, so a name is unique among the
// siblings of its group only — each application with a route slug is one, the plain names (folders, the application with
// no segment, a kind nobody claimed) another.
describe('registry: tokenInputFor', () => {
  function routed() {
    const r = createRegistry()
    const memories = r.registerApplication({ id: 'memories', name: 'm' })
    memories.kind(MEMORY_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'images' })
    memories.kind(CHAPTER_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'images' })
    memories.route('memories')
    const expenses = r.registerApplication({ id: 'expenses', name: 'e' })
    expenses.kind('POT', { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })
    expenses.route('expenses')
    const depot = r.registerApplication({ id: 'depot', name: 'd' })
    depot.kind('LEAF', { rekeyContent: nothingOfItsOwnToRekey, icon: 'file', slug: fileNameSlug })
    depot.route(null)
    return r
  }

  it('puts a claimed route slug and a slash before the kind\'s slug', () => {
    const r = routed()
    expect(r.tokenInputFor(MEMORY_KIND, 'Korfubanien')).toBe('memories/korfubanien')
    expect(r.tokenInputFor(CHAPTER_KIND, 'Tag 1')).toBe('memories/tag-1')
    expect(r.tokenInputFor('POT', 'Korfubanien')).toBe('expenses/korfubanien')
  })

  it('keeps the bare slug for a folder, the application with no segment and a kind nobody registered', () => {
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

  it('takes the folding from the kind\'s hook, the prefix from its application\'s route', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file', slug: fileNameSlug })
    testApp(r).route('leaves')
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
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', createDialog: Stub, shareDialog: Other })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', createDialog: Stub, shareDialog: Other })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file' })
    for (const k of [MEMORY_KIND, CHAPTER_KIND]) expect(r.kind(k)).toMatchObject({ createDialog: Stub, shareDialog: Other })
    const leaf = r.kind('LEAF')
    expect(leaf.supported && [leaf.createDialog, leaf.shareDialog]).toEqual([undefined, undefined])
    expect(r.kind('NOBODY')).toEqual({ supported: false, kind: 'NOBODY' }) // no dialog to find for an unregistered kind
  })

  it("an application cannot replace FOLDER's share dialog (FOLDER is write-once like every kind)", () => {
    const r = createRegistry()
    const before = r.kind(FOLDER_KIND)
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: FOLDER_KIND, icon: 'file', shareDialog: Stub })).toThrow(/already registered/)
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
    a.registerApplication({ id: 'a', name: 'a.name' }).kind(MEMORY_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'file', shareDialog: Stub })
    expect(b.applications()).toEqual([])
    expect(b.kind(MEMORY_KIND).supported).toBe(false)
    b.registerApplication({ id: 'a', name: 'a.name' }) // the id is free in the other one
  })

  it('lists every registered kind, FOLDER first, then in registration order (a copy)', () => {
    const r = createRegistry()
    expect(r.kinds()).toEqual([FOLDER_KIND])
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file' })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file' })
    expect(r.kinds()).toEqual([FOLDER_KIND, MEMORY_KIND, 'LEAF'])
    ;(r.kinds() as string[]).push('FORGED')
    expect(r.kinds()).toEqual([FOLDER_KIND, MEMORY_KIND, 'LEAF'])
  })
})

describe('registry: drop pages (share bodies)', () => {
  /** A registry whose test application owns `kinds`. */
  const owning = (...kinds: string[]) => {
    const r = createRegistry()
    for (const kind of kinds) testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind, icon: 'file' })
    return r
  }

  it('is empty until claimed: shareBodyFor() is null, not undefined', () => {
    const r = createRegistry()
    expect(r.shareBodyFor(MEMORY_KIND)).toBeNull()
  })

  it('is single-claim per key: a second claim throws and the first body stays', () => {
    const r = owning(MEMORY_KIND)
    testApp(r).shareBody(MEMORY_KIND, Stub)
    expect(() => testApp(r).shareBody(MEMORY_KIND, Other)).toThrow(/already claimed/)
    expect(r.shareBodyFor(MEMORY_KIND)).toBe(Stub)
  })

  it('answers by the claimed kind token only: a root kind nobody claimed has no body', () => {
    const r = owning('EXPENSE', MEMORY_KIND)
    testApp(r).shareBody('EXPENSE', Stub)
    expect(r.shareBodyFor('EXPENSE')).toBe(Stub)
    for (const unclaimed of [MEMORY_KIND, 'expense', 'EXPENSES', 'drop', '']) expect(r.shareBodyFor(unclaimed)).toBeNull()
    // Control: claiming one kind leaves another free to claim.
    expect(() => testApp(r).shareBody(MEMORY_KIND, Other)).not.toThrow()
    expect(r.shareBodyFor(MEMORY_KIND)).toBe(Other)
  })

  // An application draws the drop page of its OWN kind: another's kind, or one nobody registered, is refused.
  it('refuses a claim of a kind the application does not own, and keeps nothing of it', () => {
    const r = owning(MEMORY_KIND)
    const other = r.registerApplication({ id: 'other', name: 'o' })
    expect(() => other.shareBody(MEMORY_KIND, Stub)).toThrow(/not a kind of other/)
    expect(() => other.shareBody('NOBODY', Stub)).toThrow(/not a kind of other/)
    expect(r.shareBodyFor(MEMORY_KIND)).toBeNull()
    testApp(r).shareBody(MEMORY_KIND, Stub) // control: its owner may
  })

  // A WRITE_ONLY link's drop page is an application's (inbox #401 S5); no such link roots at a folder (ADR 0080 decision
  // 11), so FOLDER is claimed by nobody — an application's claim of it throws and leaves it unclaimed.
  it('refuses a claim of FOLDER: no drop page for the platform kind', () => {
    const r = createRegistry()
    expect(r.shareBodyFor(FOLDER_KIND)).toBeNull()
    expect(() => testApp(r).shareBody(FOLDER_KIND, Stub)).toThrow(/already claimed/)
    expect(r.shareBodyFor(FOLDER_KIND)).toBeNull()
  })
})

// Owner 2026-11-04: a folder's upload-only link names a new node of ONE application's kind - one registration across the
// registry, of a kind of its own whose drop page it claimed (a link nobody serves is refused).
describe('registry: the folder upload-only link', () => {
  const share = { allowed: () => true, hint: 'test.hint', create: vi.fn() }
  const owning = (...kinds: string[]) => {
    const r = createRegistry()
    for (const kind of kinds) testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind, icon: 'file' })
    return r
  }

  it('is none until registered, then the registering application\'s kind with its answer', () => {
    const r = owning('BOX')
    expect(r.folderUploadShare()).toBeNull()
    testApp(r).shareBody('BOX', Stub)
    testApp(r).folderUploadShare('BOX', share)
    expect(r.folderUploadShare()).toEqual({ kind: 'BOX', allowed: share.allowed, hint: 'test.hint', create: share.create })
    expect(Object.isFrozen(r.folderUploadShare())).toBe(true)
  })

  it('is write-once across the registry: a second registration throws, by another application too', () => {
    const r = owning('BOX')
    testApp(r).shareBody('BOX', Stub)
    testApp(r).folderUploadShare('BOX', share)
    expect(() => testApp(r).folderUploadShare('BOX', { ...share, hint: 'other' })).toThrow(/already registered/)
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'CRATE', icon: 'file' }, 'other')
    testApp(r, 'other').shareBody('CRATE', Stub)
    expect(() => testApp(r, 'other').folderUploadShare('CRATE', share)).toThrow(/already registered/)
    expect(r.folderUploadShare()?.hint).toBe('test.hint')
  })

  it('refuses another application\'s kind, FOLDER, and a kind with no drop page; keeps nothing of them', () => {
    const r = owning('BOX')
    const other = r.registerApplication({ id: 'other', name: 'o' })
    expect(() => other.folderUploadShare('BOX', share)).toThrow(/not a kind of other/)
    expect(() => testApp(r).folderUploadShare(FOLDER_KIND, share)).toThrow(/not a kind of test/)
    expect(() => testApp(r).folderUploadShare('BOX', share)).toThrow(/no drop page/)
    expect(r.folderUploadShare()).toBeNull()
    testApp(r).shareBody('BOX', Stub)
    testApp(r).folderUploadShare('BOX', share) // control: its owner may, once the page is claimed
    expect(r.folderUploadShare()?.kind).toBe('BOX')
  })
})

describe('registry: lifecycle hooks', () => {
  const hooks = (over: Partial<LifecycleHooks> = {}): LifecycleHooks => ({ onMasterKeyRotated: async () => {}, ...over })
  /** A registry that named the applications `ids`, as a composition root does; the registrars by id. */
  const named = (...ids: string[]) => {
    const r = createRegistry()
    const apps = Object.fromEntries(ids.map((id) => [id, r.registerApplication({ id, name: `${id}.name` })]))
    return Object.assign(r, { apps })
  }

  it('is write-once per application: a second registration throws and the first hooks stay', () => {
    const r = named('memories')
    const first = vi.fn()
    r.apps.memories.lifecycle(hooks({ onScopeReset: first }))
    expect(() => r.apps.memories.lifecycle(hooks({ onScopeReset: vi.fn() }))).toThrow(/already registered/)
    r.scopeReset()
    expect(first).toHaveBeenCalledTimes(1)
  })

  // Hooks go in only through an application's registrar: there is no door that takes an id an application does not hold.
  it('has no free lifecycle door: hooks exist only for an application this build names', () => {
    const r = named('memories')
    // @ts-expect-error the free `registerLifecycle(id, hooks)` is gone (owner 2026-11-01): `app.lifecycle(hooks)`
    expect(r.registerLifecycle).toBeUndefined()
  })

  it('refuses a hook set without onMasterKeyRotated (it is required, not optional)', () => {
    const r = named('x')
    expect(() => r.apps.x.lifecycle({} as LifecycleHooks)).toThrow(/onMasterKeyRotated/)
    r.apps.x.lifecycle(hooks()) // the refused registration left nothing behind: the application may still register
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
    r.apps.a.lifecycle(hooks({ onMasterKeyRotated: a }))
    r.apps.b.lifecycle(hooks({ onMasterKeyRotated: b }))
    const o = new Uint8Array([1])
    const n = new Uint8Array([2])
    await r.masterKeyRotated(o, n, ['s1'])
    expect(a).toHaveBeenCalledWith(o, n, ['s1'])
    expect(b).toHaveBeenCalledWith(o, n, ['s1'])
  })

  it('a failing hook does not stop the next application, and its error is rethrown afterwards', async () => {
    const r = named('a', 'b')
    const second = vi.fn()
    r.apps.a.lifecycle(hooks({ onScopeEntered: () => Promise.reject(new Error('boom')) }))
    r.apps.b.lifecycle(hooks({ onScopeEntered: second }))
    await expect(r.scopeEntered('s1')).rejects.toThrow('boom')
    expect(second).toHaveBeenCalledWith('s1')
  })

  it('optional hooks may be absent: scopeReset / scopeEntered are no-ops for an application that sets none', async () => {
    const r = named('a')
    r.apps.a.lifecycle(hooks())
    expect(() => r.scopeReset()).not.toThrow()
    await expect(r.scopeEntered('s1')).resolves.toBeUndefined()
  })
})

// The scoped registrar (owner 2026-11-01): an application names itself once and registers everything through what it is
// answered - its kinds are the ones it registered, its id lives in one place.
describe('registry: applications (uc.platform.switch-application-off)', () => {
  it('derives ownership from the registrations: a kind answers the application that registered it, a folder and a stranger none', () => {
    const r = createRegistry()
    const memories = r.registerApplication({ id: 'memories', name: 'memory.memories' })
    memories.kind(MEMORY_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'images' })
    const depot = r.registerApplication({ id: 'depot', name: 'depot.title' })
    depot.kind('FILE', { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })
    memories.kind(CHAPTER_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'images' }) // in any order
    depot.kind('DEPOT_DROPBOX', { rekeyContent: nothingOfItsOwnToRekey, icon: 'inbox' })
    expect(r.applications()).toEqual([{ id: 'memories', name: 'memory.memories' }, { id: 'depot', name: 'depot.title' }])
    expect([MEMORY_KIND, CHAPTER_KIND, 'FILE', 'DEPOT_DROPBOX'].map((k) => r.applicationOf(k))).toEqual(['memories', 'memories', 'depot', 'depot'])
    expect(r.applicationOf(FOLDER_KIND)).toBeNull()
    expect(r.applicationOf('NOBODY')).toBeNull()
    expect(memories.id).toBe('memories')
  })

  it('refuses an id twice, and a kind another application registered — keeping nothing of the refused one', () => {
    const r = createRegistry()
    const memories = r.registerApplication({ id: 'memories', name: 'memory.memories' })
    memories.kind(MEMORY_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'images' })
    expect(() => r.registerApplication({ id: 'memories', name: 'x' })).toThrow(/already registered/)
    const other = r.registerApplication({ id: 'other', name: 'x' })
    expect(() => other.kind(MEMORY_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })).toThrow(/already registered/)
    expect(() => other.kind(FOLDER_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })).toThrow(/already registered/)
    expect(r.applicationOf(MEMORY_KIND)).toBe('memories')
    expect(r.applicationOf(FOLDER_KIND)).toBeNull()
    expect(r.kind(MEMORY_KIND)).toMatchObject({ icon: 'images' })
    expect(r.applications().map((a) => a.id)).toEqual(['memories', 'other'])
  })

  // `app.id` is the one place the id lives: a kind's content schema names its application from it.
  it('fills a kind\'s content schema in with its application\'s id', () => {
    const r = createRegistry()
    r.registerApplication({ id: 'pots', name: 'p' }).kind('POT', { rekeyContent: nothingOfItsOwnToRekey, icon: 'wallet', contentSchema: { version: 3 } })
    r.registerApplication({ id: 'bare', name: 'b' }).kind('BARE', { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })
    expect(r.kind('POT')).toMatchObject({ contentSchema: { application: 'pots', version: 3 } })
    expect(r.kind('BARE')).not.toHaveProperty('contentSchema')
  })

  it('has no free register functions: everything an application registers goes through its registrar', () => {
    const r = createRegistry()
    for (const gone of ['registerKind', 'claimRouteSlug', 'claimNoRouteSegment', 'registerLifecycle', 'claimShareBody', 'claimPersonSection', 'claimItemReview']) {
      expect((r as unknown as Record<string, unknown>)[gone], gone).toBeUndefined()
    }
    expect(Object.isFrozen(r.registerApplication({ id: 'a', name: 'a' })), 'a registrar cannot be patched').toBe(true)
  })
})

describe('registry: routes', () => {
  /** A registry with Memories (slug `memories`) and Depot (no segment), as the composition root registers them. */
  function routedApps() {
    const r = createRegistry()
    const memories = r.registerApplication({ id: 'memories', name: 'm' })
    memories.kind(MEMORY_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'images' })
    memories.kind(CHAPTER_KIND, { rekeyContent: nothingOfItsOwnToRekey, icon: 'images' })
    memories.route('memories')
    const depot = r.registerApplication({ id: 'depot', name: 'd' })
    depot.kind('FILE', { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })
    depot.route(null)
    depot.kind('DEPOT_DROPBOX', { rekeyContent: nothingOfItsOwnToRekey, icon: 'inbox' }) // a kind after the decision is covered too
    return { r, memories, depot }
  }

  it('maps every kind of an application to its slug and group, a folder and the rest to none', () => {
    const { r } = routedApps()
    expect(r.routeOf(MEMORY_KIND)).toEqual({ slug: 'memories', group: 'application:memories' })
    expect(r.routeOf(CHAPTER_KIND)).toEqual({ slug: 'memories', group: 'application:memories' })
    expect(r.routeOf('FILE')).toEqual({ slug: null, group: 'none' })
    expect(r.routeOf('DEPOT_DROPBOX')).toEqual(r.routeOf('FILE'))
    expect(r.routeOf(FOLDER_KIND)).toEqual({ slug: null, group: 'folder' })
    // a kind nobody registered has no segment and numbers among the plain names of the segment-less application
    expect(r.routeOf('FUTURE')).toEqual({ slug: null, group: 'none' })
    expect(r.routeSlugs()).toEqual(['memories'])
  })

  it('is decided once per application: a second decision throws, whichever, and keeps the first', () => {
    const { r, memories, depot } = routedApps()
    expect(() => memories.route('memoirs')).toThrow(/already decided/)
    expect(() => memories.route(null)).toThrow(/already decided/)
    expect(() => depot.route('files')).toThrow(/already decided/)
    expect(r.routeOf(MEMORY_KIND).slug).toBe('memories')
    expect(r.routeSlugs(), 'a refused decision claims no slug').toEqual(['memories'])
  })

  it('refuses a slug another application claimed, and keeps it the first one\'s', () => {
    const { r } = routedApps()
    const other = r.registerApplication({ id: 'other', name: 'o' })
    other.kind('OTHER', { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })
    expect(() => other.route('memories')).toThrow(/already claimed/)
    expect(r.routeOf('OTHER').slug).toBeNull()
    expect(r.routeDecided('OTHER'), 'the refused decision decided nothing').toBe(false)
    other.route('others') // control: the application may still decide
    expect(r.routeOf('OTHER')).toEqual({ slug: 'others', group: 'application:others' })
  })

  it('lets one application declare it has no segment — a second one cannot share the plain names', () => {
    const { r } = routedApps()
    const invoices = r.registerApplication({ id: 'invoices', name: 'i' })
    invoices.kind('INVOICE', { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })
    expect(() => invoices.route(null)).toThrow(/no route segment/)
    expect(r.routeOf('INVOICE')).toEqual({ slug: null, group: 'none' }) // undecided, but only as any unknown kind is
  })

  it('tells a kind whose route was decided (a slug, or no segment) from one whose application decided nothing, which routes alike', () => {
    const { r } = routedApps()
    r.registerApplication({ id: 'invoices', name: 'i' }).kind('INVOICE', { rekeyContent: nothingOfItsOwnToRekey, icon: 'file' })
    expect([MEMORY_KIND, 'FILE'].map((k) => r.routeDecided(k))).toEqual([true, true])
    expect(r.routeOf('INVOICE')).toEqual(r.routeOf('FILE')) // the premise: an undecided kind shares the plain names…
    expect(r.routeDecided('INVOICE')).toBe(false) // …and yet decided nothing
    expect(r.routeDecided('NOBODY')).toBe(false) // a kind nobody registered
    expect(r.routeDecided(FOLDER_KIND)).toBe(false) // the platform's, decided by no application
  })

  it('refuses a slug that is no slug, and the words the platform keeps', () => {
    const r = createRegistry()
    const app = r.registerApplication({ id: 'a', name: 'a' })
    for (const bad of ['', 'Memories', 'a b', '-a', 'a-', 'a--b', '1a', 'a/b', '~me', '@g', 'a%2Fb']) {
      expect(() => app.route(bad), bad).toThrow(/not a slug/)
    }
    expect(RESERVED_ROUTE_SEGMENTS.length).toBeGreaterThan(0)
    for (const word of RESERVED_ROUTE_SEGMENTS) {
      expect(() => app.route(word), word).toThrow(/reserved/)
    }
    // the platform's history-mode routes are among them
    for (const word of ['login', 'settings', 'invitations', 's', 'api', 'people']) expect(RESERVED_ROUTE_SEGMENTS).toContain(word)
    expect(() => app.route('people'), 'the profile route (ADR 0083)').toThrow(/reserved/)
    expect(r.routeSlugs()).toEqual([])
    app.route('fine') // every refusal left the decision open
  })
})

// A kind whose nodes are no row (a future application's one pool per space, `docs/apps/inventory.md`).
describe('registry: kinds that are no row (listed: false)', () => {
  it('answers listed for every kind but one registered listed: false - a kind nobody registered is listed (as the unsupported row)', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'POOL', icon: 'inbox', listed: false })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'ITEM', icon: 'file' })
    expect(['POOL', 'ITEM', FOLDER_KIND, 'NOBODY'].map((k) => r.listed(k))).toEqual([false, true, true, true])
  })

  it('refuses a kind that is not listed but names a list, and registers nothing of it', () => {
    const r = createRegistry()
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'POOL', icon: 'inbox', listed: false, list: { rows: () => ({ row: () => null }) } }))
      .toThrow(/not listed but names a list/)
    expect(r.kind('POOL').supported).toBe(false)
    expect(r.lists()).toEqual([])
  })
})

describe('registry: the level "New" menu (inbox #406)', () => {
  const allow = () => true
  const entry = (order: number, label = 'k.label') => ({ label, icon: 'plus' as const, order, allowed: allow })

  it('is empty until a kind brings an entry', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file' })
    expect(r.levelMenu()).toEqual([])
  })

  it('lists every create entry (id create:<KIND>) and level action by order, a tie in registration order', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'B', icon: 'file', createDialog: Stub, createEntry: entry(20, 'b.new') })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', createDialog: Stub, createEntry: entry(10, 'a.new'), levelActions: [{ id: 'a.up', ...entry(30, 'a.up') }] })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'C', icon: 'file', createDialog: Stub, createEntry: entry(20, 'c.new') })
    expect(r.levelMenu().map((e) => [e.id, e.create ?? null])).toEqual([['create:A', 'A'], ['create:B', 'B'], ['create:C', 'C'], ['a.up', null]])
  })

  it('refuses a create entry without a createDialog to open, and registers nothing', () => {
    const r = createRegistry()
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', createEntry: entry(1) })).toThrow(/no createDialog/)
    expect(r.kind('A')).toEqual({ supported: false, kind: 'A' })
  })

  it('is write-once for level action ids: a taken id, a duplicate within a kind and the reserved create: prefix throw', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'A', icon: 'file', levelActions: [{ id: 'a.up', ...entry(1) }] })
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'B', icon: 'file', levelActions: [{ id: 'a.up', ...entry(2) }] })).toThrow(/already registered/)
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'C', icon: 'file', levelActions: [{ id: 'c.1', ...entry(1) }, { id: 'c.1', ...entry(2) }] })).toThrow(/already registered/)
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'D', icon: 'file', levelActions: [{ id: 'create:FOLDER', ...entry(1) }] })).toThrow(/already registered/)
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
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'LEAF', icon: 'file' })
    expect(r.lists()).toEqual([])
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file' })
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, tabLabel: 'x.mem' } })).toThrow(/already registered/)
    expect(r.lists()).toEqual([])
    expect(r.tabs()).toEqual([])
  })

  it('hands out copies: mutating what lists() and tabs() returned changes nothing', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, tabLabel: 'x.mem' } })
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
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW, map] } })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'OTHER', icon: 'file', list: { rows: rowsB } })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(r.lists()).toEqual([
      { rows: rowsA, kinds: [MEMORY_KIND, CHAPTER_KIND], views: [GRID_VIEW, map] },
      { rows: rowsB, kinds: ['OTHER'], views: [] },
    ])
  })

  it('refuses a kind naming a shared factory with other views, and registers nothing of it', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(() => testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW] } }))
      .toThrow(/CHAPTER names the row source of MEMORY with views \[grid\], not \[grid, map\]/)
    expect(r.kind(CHAPTER_KIND)).toEqual({ supported: false, kind: CHAPTER_KIND })
    expect(r.lists()).toHaveLength(1)
    // the refused kind added no tab either
    expect(r.tabs()).toEqual([])
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', list: { rows: rowsA, views: [GRID_VIEW, map] } })
    expect(r.lists()).toEqual([{ rows: rowsA, kinds: [MEMORY_KIND, CHAPTER_KIND], views: [GRID_VIEW, map] }])
  })

  it('derives one tab per distinct label, in registration order: kinds sharing a label share a tab, the id is its first kind', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: MEMORY_KIND, icon: 'file', list: { rows: rowsA, tabLabel: 'x.mem' } })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'FILE', icon: 'file', list: { rows: rowsB, tabLabel: 'x.files' } })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: CHAPTER_KIND, icon: 'file', list: { rows: rowsA, tabLabel: 'x.mem' } })
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: 'NOTAB', icon: 'file', list: { rows: rowsB } })
    expect(r.tabs().map((t) => ({ id: t.id, label: t.label, kinds: t.kinds }))).toEqual([
      { id: MEMORY_KIND, label: 'x.mem', kinds: [MEMORY_KIND, CHAPTER_KIND] },
      { id: 'FILE', label: 'x.files', kinds: ['FILE'] },
    ])
  })

  it('gives every menu entry the kind that declared it', () => {
    const r = createRegistry()
    testKind(r, { rekeyContent: nothingOfItsOwnToRekey,
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
    testApp(r).personSection(A)
    testApp(r, 'other').personSection(B)
    expect(r.personSections()).toEqual([A, B])
    expect(() => testApp(r).personSection(A)).toThrow(/already claimed/)
    expect(() => testApp(r, 'other').personSection(A), 'nor by another application').toThrow(/already claimed/)
  })
})

describe('registry: item reviews', () => {
  it('lists the claimed reviews in order and refuses the same component twice', () => {
    const r = createRegistry()
    const A = { name: 'A' }
    expect(r.itemReviews()).toEqual([])
    testApp(r).itemReview(A)
    expect(r.itemReviews()).toEqual([A])
    expect(() => testApp(r, 'other').itemReview(A)).toThrow(/already claimed/)
  })
})

