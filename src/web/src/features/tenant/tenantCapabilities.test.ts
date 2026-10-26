// uc.admin.tenant-switch-capabilities (web) — the tenant owner's register as a tree: the state is the server's
// `effective`/`serverOff`, and "off through" names the nearest ancestor the TENANT switched off.
import { describe, it, expect } from 'vitest'
import { erasureNote, hasDescendants, inLocale, tenantCapabilityTree, type TenantCapabilitySwitch } from './tenantCapabilities'

const text = { en: 'x', de: 'y' }
function entry(id: string, over: Partial<TenantCapabilitySwitch> = {}): TenantCapabilitySwitch {
  return { id, title: { en: id, de: id }, purpose: text, whenOff: text, off: false, serverOff: false, effective: true, requirements: [], ...over }
}

describe('tenantCapabilityTree', () => {
  it('indents a child under its parent and names the parent it is off through', () => {
    const tree = tenantCapabilityTree([
      entry('core.public-shares', { off: true, effective: false }),
      entry('core.public-shares.contribute', { effective: false }),
      entry('core.group-space'),
    ])
    expect(tree.map((n) => [n.entry.id, n.depth, n.state.kind])).toEqual([
      ['core.public-shares', 0, 'off'],
      ['core.public-shares.contribute', 1, 'offThrough'],
      ['core.group-space', 0, 'on'],
    ])
    const through = tree[1]!.state
    expect(through.kind === 'offThrough' && through.through.id).toBe('core.public-shares')
  })

  it('says the server has it off when no tenant switch explains it, and the tenant\'s own off first', () => {
    const tree = tenantCapabilityTree([
      entry('core.group-space', { serverOff: true, effective: false }),
      entry('core.public-shares', { off: true, serverOff: true, effective: false }),
    ])
    expect(tree.map((n) => n.state.kind)).toEqual(['serverOff', 'off'])
  })

  it('finds descendants by the id path and reads the locale\'s text, English otherwise', () => {
    const all = [entry('core.public-shares'), entry('core.public-shares.drop-box'), entry('core.group-space')]
    expect(hasDescendants('core.public-shares', all)).toBe(true)
    expect(hasDescendants('core.group-space', all)).toBe(false)
    expect(inLocale({ en: 'Groups', de: 'Gruppen' }, 'de')).toBe('Gruppen')
    expect(inLocale({ en: 'Groups', de: 'Gruppen' }, 'fr')).toBe('Groups')
  })
})

// uc.platform.erase-switched-off-application (web): the owner's row names the day only where it is true — a switch-on
// here cancels it before the day, only the operator's can while the instance holds it off, and none can after it.
describe('erasureNote', () => {
  const now = new Date('2026-11-01T12:00:00Z')
  const day = new Date('2026-11-25T10:00:00Z')

  it('names the day the tenant owner can still cancel, and none for an entry without one', () => {
    expect(erasureNote(entry('depot', { off: true, effective: false, erasureDueAt: day }), now)).toEqual({ kind: 'day', day })
    expect(erasureNote(entry('depot', { off: true, effective: false }), now)).toBeNull() // control: no day served, none shown
  })

  it('names the operator as the one who can cancel while the instance holds it off, the tenant\'s own switch off too', () => {
    expect(erasureNote(entry('depot', { serverOff: true, effective: false, erasureDueAt: day }), now)).toEqual({ kind: 'byServer', day })
    expect(erasureNote(entry('depot', { off: true, serverOff: true, effective: false, erasureDueAt: day }), now))
      .toEqual({ kind: 'byServer', day })
  })

  it('promises no cancel once the day has come, switched on again or not', () => {
    const past = new Date('2026-10-31T10:00:00Z')
    expect(erasureNote(entry('depot', { off: true, effective: false, erasureDueAt: past }), now)).toEqual({ kind: 'running', day: past })
    // Switched on after the day: the server keeps the row until the erasure finished; what existed still goes.
    expect(erasureNote(entry('depot', { effective: true, erasureDueAt: past }), now)).toEqual({ kind: 'running', day: past })
  })

  it('names no coming day for an entry in effect', () => {
    expect(erasureNote(entry('depot', { effective: true, erasureDueAt: day }), now)).toBeNull()
  })
})
