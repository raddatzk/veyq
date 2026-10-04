import { describe, it, expect } from 'vitest'
import { defineComponent } from 'vue'
import { createRegistry } from './registry'
import { NodeKind } from '../../api/generated/content'

const Stub = defineComponent({ template: '<div />' })
const Other = defineComponent({ template: '<span />' })

describe('registry: kinds', () => {
  it('holds only the platform kind (FOLDER) until an application registers its own', () => {
    const r = createRegistry()
    expect(r.kind(NodeKind.Folder)).toMatchObject({ supported: true, icon: 'pi pi-folder', shareable: false })
    expect(r.kind(NodeKind.Memory)).toEqual({ supported: false, kind: NodeKind.Memory })
  })

  it('is write-once: registering the same kind twice throws and keeps the first descriptor', () => {
    const r = createRegistry()
    r.registerKind({ kind: NodeKind.Memory, icon: 'pi pi-a', shareable: true })
    expect(() => r.registerKind({ kind: NodeKind.Memory, icon: 'pi pi-b', shareable: false })).toThrow(/already registered/)
    expect(r.kind(NodeKind.Memory)).toMatchObject({ supported: true, icon: 'pi pi-a', shareable: true })
  })

  it('refuses an application re-registering the platform FOLDER', () => {
    const r = createRegistry()
    expect(() => r.registerKind({ kind: NodeKind.Folder, icon: 'x', shareable: true })).toThrow(/already registered/)
    expect(r.kind(NodeKind.Folder)).toMatchObject({ icon: 'pi pi-folder', shareable: false })
  })

  it('resolves an unregistered kind to the explicit unsupported result, never undefined', () => {
    const r = createRegistry()
    const got = r.kind(NodeKind.Chapter)
    expect(got).toBeDefined()
    expect(got.supported).toBe(false)
    // A kind outside the closed enum (a newer server) is just as explicit.
    expect(createRegistry().kind('FUTURE' as NodeKind)).toEqual({ supported: false, kind: 'FUTURE' })
  })

  it('copies the descriptor: mutating the caller object afterwards does not change the registration', () => {
    const r = createRegistry()
    const d = { kind: NodeKind.Memory, icon: 'pi pi-a', shareable: true }
    r.registerKind(d)
    d.shareable = false
    expect(r.kind(NodeKind.Memory)).toMatchObject({ shareable: true })
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
    a.registerKind({ kind: NodeKind.Memory, icon: 'x', shareable: true })
    expect(b.slot('shareDialog')).toBeNull()
    expect(b.kind(NodeKind.Memory).supported).toBe(false)
  })
})
