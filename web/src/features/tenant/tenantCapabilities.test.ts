// uc.admin.tenant-switch-capabilities (web) — the tenant owner's register as a tree: the state is the server's
// `effective`/`serverOff`, and "off through" names the nearest ancestor the TENANT switched off.
import { describe, it, expect } from 'vitest'
import { hasDescendants, inLocale, tenantCapabilityTree, type TenantCapabilitySwitch } from './tenantCapabilities'

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
