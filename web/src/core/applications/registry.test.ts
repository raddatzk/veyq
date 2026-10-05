import { describe, it, expect, vi } from 'vitest'
import { defineComponent } from 'vue'
import { createRegistry, DROP_SHARE, type LifecycleHooks } from './registry'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../../features/memory/kinds'

const Stub = defineComponent({ template: '<div />' })
const Other = defineComponent({ template: '<span />' })

describe('registry: kinds', () => {
  it('holds only the platform kind (FOLDER) until an application registers its own', () => {
    const r = createRegistry()
    expect(r.kind(FOLDER_KIND)).toMatchObject({ supported: true, icon: 'pi pi-folder', shareable: false })
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
    expect(() => r.registerKind({ kind: FOLDER_KIND, icon: 'x', shareable: true })).toThrow(/already registered/)
    expect(r.kind(FOLDER_KIND)).toMatchObject({ icon: 'pi pi-folder', shareable: false })
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

  it('keeps the drop key outside the kind token space: no kind can claim its share body', () => {
    // A kind token is upper-case (`_common` NodeKind); the drop key is lower-case, so the two never meet.
    expect(/^[A-Z][A-Z0-9_]{0,15}$/.test(DROP_SHARE)).toBe(false)
    expect(/^[A-Z][A-Z0-9_]{0,15}$/.test(FOLDER_KIND)).toBe(true)
    const r = createRegistry()
    r.claimShareBody(DROP_SHARE, Stub)
    expect(r.shareBody(DROP_SHARE.toUpperCase())).toBeNull()
  })

  it('copies the descriptor: mutating the caller object afterwards does not change the registration', () => {
    const r = createRegistry()
    const d = { kind: MEMORY_KIND, icon: 'pi pi-a', shareable: true }
    r.registerKind(d)
    d.shareable = false
    expect(r.kind(MEMORY_KIND)).toMatchObject({ shareable: true })
  })
})

describe('registry: slots', () => {
  it('is empty until claimed: slot() is null, not undefined', () => {
    expect(createRegistry().slot('topbarTray')).toBeNull()
  })

  it('is single-claim: a second claim of the same slot throws and the first claimant stays', () => {
    const r = createRegistry()
    r.claimSlot('levelView', Stub)
    expect(() => r.claimSlot('levelView', Other)).toThrow(/already claimed/)
    expect(r.slot('levelView')).toBe(Stub)
  })

  it('keeps slots independent: claiming one does not claim another', () => {
    const r = createRegistry()
    r.claimSlot('sidebarPins', Stub)
    expect(r.slot('topbarTray')).toBeNull()
    expect(() => r.claimSlot('topbarTray', Other)).not.toThrow()
  })

  it('two registries do not share state', () => {
    const a = createRegistry()
    const b = createRegistry()
    a.claimSlot('shareDialog', Stub)
    a.registerKind({ kind: MEMORY_KIND, icon: 'x', shareable: true })
    expect(b.slot('shareDialog')).toBeNull()
    expect(b.kind(MEMORY_KIND).supported).toBe(false)
  })
})

describe('registry: share bodies', () => {
  it('is empty until claimed: shareBody() is null, not undefined', () => {
    const r = createRegistry()
    expect(r.shareBody(MEMORY_KIND)).toBeNull()
    expect(r.shareBody(DROP_SHARE)).toBeNull()
  })

  it('is single-claim per key: a second claim throws and the first body stays', () => {
    const r = createRegistry()
    r.claimShareBody(MEMORY_KIND, Stub)
    expect(() => r.claimShareBody(MEMORY_KIND, Other)).toThrow(/already claimed/)
    expect(r.shareBody(MEMORY_KIND)).toBe(Stub)
  })

  it('keeps the kind-less drop apart from every root kind: claiming one does not claim the other', () => {
    const r = createRegistry()
    r.claimShareBody(DROP_SHARE, Stub)
    expect(r.shareBody(MEMORY_KIND)).toBeNull()
    expect(() => r.claimShareBody(MEMORY_KIND, Other)).not.toThrow()
    expect(() => r.claimShareBody(DROP_SHARE, Other)).toThrow(/drop is already claimed/)
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
