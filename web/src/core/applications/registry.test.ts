import { describe, it, expect, vi } from 'vitest'
import { defineComponent } from 'vue'
import { createRegistry, type LifecycleHooks } from './registry'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../../features/memory/kinds'
import { fileNameSlug, slugify } from '../../shared/naming'

const Stub = defineComponent({ template: '<div />' })
const Other = defineComponent({ template: '<span />' })

describe('registry: kinds', () => {
  // FOLDER is a share root since cr-node-key-hierarchy 5b.3 (core's own FolderShareDialog; ADR 0080 decision 12).
  it('holds only the platform kind (FOLDER, shareable) until an application registers its own', () => {
    const r = createRegistry()
    expect(r.kind(FOLDER_KIND)).toMatchObject({ supported: true, icon: 'pi pi-folder', shareable: true })
    expect(r.kind(MEMORY_KIND)).toEqual({ supported: false, kind: MEMORY_KIND })
  })

  it('is write-once: registering the same kind twice throws and keeps the first descriptor', () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, icon: 'pi pi-a', shareable: true })
    expect(() => r.registerKind({ kind: MEMORY_KIND, icon: 'pi pi-b', shareable: false })).toThrow(/already registered/)
    expect(r.kind(MEMORY_KIND)).toMatchObject({ supported: true, icon: 'pi pi-a', shareable: true })
  })

  it('refuses an application re-registering the platform FOLDER', () => {
    const r = createRegistry()
    expect(() => r.registerKind({ kind: FOLDER_KIND, icon: 'x', shareable: false })).toThrow(/already registered/)
    expect(r.kind(FOLDER_KIND)).toMatchObject({ icon: 'pi pi-folder', shareable: true })
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
    r.registerKind({ kind: 'EXPENSE', icon: 'pi pi-wallet', shareable: false })
    expect(r.kind('EXPENSE')).toMatchObject({ supported: true, kind: 'EXPENSE', icon: 'pi pi-wallet' })
    for (const unknown of ['INVOICE', 'expense', 'EXPENSES', '']) {
      expect(r.kind(unknown)).toEqual({ supported: false, kind: unknown })
    }
    // Control: the platform's own kind is there without any registration.
    expect(r.kind(FOLDER_KIND)).toMatchObject({ supported: true, kind: 'FOLDER' })
  })

  it('copies the descriptor: mutating the caller object afterwards does not change the registration', () => {
    const r = createRegistry()
    const d = { kind: MEMORY_KIND, icon: 'pi pi-a', shareable: true }
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
    r.registerKind({ kind: MEMORY_KIND, icon: 'pi pi-a', shareable: true })
    expect(r.slugFor(FOLDER_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG'))
    expect(r.slugFor(MEMORY_KIND, 'Day1.md')).toBe('day1-md')
  })

  it('folds with slugify for a kind nobody registered (a token is still computable for it)', () => {
    expect(createRegistry().slugFor('FUTURE', 'Day1.md')).toBe('day1-md')
  })

  it('folds with the kind\'s own hook when it registers one, and only for that kind', () => {
    const r = createRegistry()
    r.registerKind({ kind: 'LEAF', icon: 'pi pi-file', shareable: false, slug: fileNameSlug })
    expect(r.slugFor('LEAF', 'Day1.md')).toBe('day1.md')
    expect(r.slugFor('LEAF', 'Foto.JPG')).toBe(r.slugFor('LEAF', 'foto.jpg'))
    expect(r.slugFor(FOLDER_KIND, 'Day1.md')).toBe('day1-md')
    expect(r.kind('LEAF')).toMatchObject({ supported: true, slug: fileNameSlug })
  })

  it('keeps the first hook: a second registration of the kind throws and does not swap the folding', () => {
    const r = createRegistry()
    r.registerKind({ kind: 'LEAF', icon: 'pi pi-file', shareable: false, slug: fileNameSlug })
    expect(() => r.registerKind({ kind: 'LEAF', icon: 'pi pi-file', shareable: false, slug: () => 'x' })).toThrow(/already registered/)
    expect(r.slugFor('LEAF', 'A.B')).toBe('a.b')
  })
})

describe('registry: slots', () => {
  it('is empty until claimed: slot() is null, not undefined', () => {
    expect(createRegistry().slot('nodeMutationDialog')).toBeNull()
  })

  it('is single-claim: a second claim of the same slot throws and the first claimant stays', () => {
    const r = createRegistry()
    r.claimSlot('nodeMutationDialog', Stub)
    expect(() => r.claimSlot('nodeMutationDialog', Other)).toThrow(/already claimed/)
    expect(r.slot('nodeMutationDialog')).toBe(Stub)
  })

  it('keeps slots independent: claiming one does not claim another', () => {
    const r = createRegistry()
    r.claimSlot('nodeMutationDialog', Stub)
    expect(r.slot('shareDialog')).toBeNull()
    expect(() => r.claimSlot('shareDialog', Other)).not.toThrow()
  })

  it('two registries do not share state', () => {
    const a = createRegistry()
    const b = createRegistry()
    a.claimSlot('shareDialog', Stub)
    a.addSlotItem('topbarTray', 'a.tray', Stub)
    a.registerKind({ kind: MEMORY_KIND, icon: 'x', shareable: true })
    expect(b.slot('shareDialog')).toBeNull()
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

  it('refuses claimSlot on a list slot (a JS caller gets an error, not a silent claim) and addSlotItem on a single one', () => {
    const r = createRegistry()
    // The types refuse all three (each `@ts-expect-error` turns `npm run typecheck` red if a type widens again); the
    // runtime guard is for a caller the type checker does not see.
    // @ts-expect-error a list slot is not claimable
    expect(() => r.claimSlot('topbarTray', Stub)).toThrow(/topbarTray is a list slot/)
    // @ts-expect-error a list slot is not claimable
    expect(() => r.claimSlot('sidebarPins', Stub)).toThrow(/sidebarPins is a list slot/)
    // @ts-expect-error a single-claim slot takes no items
    expect(() => r.addSlotItem('shareDialog', 'a', Stub)).toThrow(/shareDialog is not a list slot/)
    expect(r.slotItems('topbarTray')).toEqual([])
    expect(r.slot('shareDialog')).toBeNull()
  })
})

// cr-files-application P4b: the folder level view is core's; a kind brings its section with its descriptor.
describe('registry: level sections', () => {
  it('is empty until a kind brings one, and the level view slot is gone', () => {
    const r = createRegistry()
    expect(r.sections()).toEqual([])
    // @ts-expect-error the level view is core's since P4b, no slot
    expect(r.slot('levelView')).toBeNull()
  })

  it('sorts the sections by order, not by registration', () => {
    const r = createRegistry()
    r.registerKind({ kind: 'LATE', icon: 'x', shareable: false, section: { order: 20, component: Other } })
    r.registerKind({ kind: 'EARLY', icon: 'x', shareable: false, section: { order: 10, component: Stub } })
    expect(r.sections()).toEqual([
      { component: Stub, order: 10, kinds: ['EARLY'] },
      { component: Other, order: 20, kinds: ['LATE'] },
    ])
  })

  it('keeps registration order on a tie (the order each section was first named)', () => {
    const r = createRegistry()
    r.registerKind({ kind: 'ONE', icon: 'x', shareable: false, section: { order: 10, component: Other } })
    r.registerKind({ kind: 'TWO', icon: 'x', shareable: false, section: { order: 10, component: Stub } })
    expect(r.sections().map((s) => s.component)).toEqual([Other, Stub])
  })

  it('keys a section by its component: two kinds naming one component are ONE section carrying both kinds', () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, icon: 'x', shareable: true, section: { order: 10, component: Stub } })
    r.registerKind({ kind: 'OTHER', icon: 'x', shareable: false, section: { order: 5, component: Other } })
    r.registerKind({ kind: CHAPTER_KIND, icon: 'x', shareable: true, section: { order: 10, component: Stub } })
    expect(r.sections()).toEqual([
      { component: Other, order: 5, kinds: ['OTHER'] },
      { component: Stub, order: 10, kinds: [MEMORY_KIND, CHAPTER_KIND] },
    ])
  })

  it('refuses a kind naming a shared section with another order, and registers nothing of it', () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, icon: 'x', shareable: true, section: { order: 10, component: Stub } })
    expect(() => r.registerKind({ kind: CHAPTER_KIND, icon: 'x', shareable: true, section: { order: 11, component: Stub } }))
      .toThrow(/CHAPTER names the section of MEMORY with order 11, not 10/)
    expect(r.kind(CHAPTER_KIND).supported).toBe(false)
    expect(r.sections()).toEqual([{ component: Stub, order: 10, kinds: [MEMORY_KIND] }])
  })

  it('a kind without a section adds none, and a refused re-registration does not add its section', () => {
    const r = createRegistry()
    r.registerKind({ kind: 'LEAF', icon: 'x', shareable: false })
    expect(r.sections()).toEqual([])
    r.registerKind({ kind: MEMORY_KIND, icon: 'x', shareable: true })
    expect(() => r.registerKind({ kind: MEMORY_KIND, icon: 'x', shareable: true, section: { order: 1, component: Stub } })).toThrow(/already registered/)
    expect(r.sections()).toEqual([])
  })

  it('hands out a copy: mutating what sections() returned changes nothing', () => {
    const r = createRegistry()
    r.registerKind({ kind: MEMORY_KIND, icon: 'x', shareable: true, section: { order: 10, component: Stub } })
    const got = r.sections() as unknown as { kinds: string[] }[]
    got[0].kinds.push('FORGED')
    ;(got as unknown[]).length = 0
    expect(r.sections()).toEqual([{ component: Stub, order: 10, kinds: [MEMORY_KIND] }])
  })
})

describe('registry: share bodies', () => {
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
