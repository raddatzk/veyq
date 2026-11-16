// A link invite's private label (ADR 0129 decision 7): sealed as a tenant value bound to its invite, so it opens for that
// invite under the tenant key and for nothing else — the swap a server could make between two invites included.
import { describe, expect, it } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { INVITE_LABEL_MAX_LENGTH, inviteLabelContext, normalizeInviteLabel, openInviteLabel, sealInviteLabel } from './inviteLabel'
import { openValue } from './valueKey'
import { fromB64std } from '@veyq/crypto/base64'
import type { ScopeKey } from './epochs'

const TENANT = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const A = { scopeId: TENANT, inviteId: '1f2d3e4a-5b6c-4c3f-8e2a-9b1d4a6e8b2c' }
const B = { scopeId: TENANT, inviteId: '2a3b4c5d-6e7f-4a1b-8c2d-3e4f5a6b7c8d' }
const KEY: ScopeKey = { epoch: 3, key: v1.randomBytes(32) }

describe('inviteLabel', () => {
  it('opens for its invite under the tenant key', () => {
    const sealed = sealInviteLabel(KEY, A, 'Oma')
    expect(openInviteLabel(KEY, A, sealed.sealedLabel, sealed.wrappedLabelValueKey)).toBe('Oma')
  })

  it('does not open as another invite\'s: a label moved onto invite B reads as none', () => {
    const sealed = sealInviteLabel(KEY, A, 'Oma')
    expect(openInviteLabel(KEY, B, sealed.sealedLabel, sealed.wrappedLabelValueKey)).toBeNull()
    // B's own wrap beside A's label opens B's value key, under which A's label does not open either
    const other = sealInviteLabel(KEY, B, 'Kollege')
    expect(openInviteLabel(KEY, B, sealed.sealedLabel, other.wrappedLabelValueKey)).toBeNull()
    // control: B's own pair opens
    expect(openInviteLabel(KEY, B, other.sealedLabel, other.wrappedLabelValueKey)).toBe('Kollege')
  })

  it('binds the invite into the label itself, not only into its key\'s wrap', () => {
    // Open A's label with A's genuine value key, but asking as B: the field seal's own binding refuses it.
    const sealed = sealInviteLabel(KEY, A, 'Oma')
    const open = (address: typeof A) => openValue(KEY, inviteLabelContext(A), sealed.wrappedLabelValueKey, (k) =>
      v1.openField(inviteLabelContext(address), k, fromB64std(sealed.sealedLabel)))
    expect(() => open(B)).toThrow()
    expect(new TextDecoder().decode(open(A))).toBe('Oma') // control
  })

  it('does not open under another key or at another epoch, and is none without a wrap', () => {
    const sealed = sealInviteLabel(KEY, A, 'Oma')
    expect(openInviteLabel({ epoch: 3, key: v1.randomBytes(32) }, A, sealed.sealedLabel, sealed.wrappedLabelValueKey)).toBeNull()
    expect(openInviteLabel({ epoch: 4, key: KEY.key }, A, sealed.sealedLabel, sealed.wrappedLabelValueKey)).toBeNull()
    expect(openInviteLabel(KEY, A, sealed.sealedLabel, null)).toBeNull()
    expect(openInviteLabel(KEY, A, null, sealed.wrappedLabelValueKey)).toBeNull()
  })

  it('mints a fresh value key per write, so two seals of one label differ', () => {
    const one = sealInviteLabel(KEY, A, 'Oma')
    const two = sealInviteLabel(KEY, A, 'Oma')
    expect(one.sealedLabel).not.toBe(two.sealedLabel)
    expect(one.wrappedLabelValueKey).not.toBe(two.wrappedLabelValueKey)
  })

  it('stores a label trimmed and bounded, and nothing for a blank one', () => {
    expect(normalizeInviteLabel('  Oma  ')).toBe('Oma')
    expect(normalizeInviteLabel('   ')).toBeNull()
    expect(normalizeInviteLabel(null)).toBeNull()
    const long = '👵'.repeat(INVITE_LABEL_MAX_LENGTH + 5)
    expect(Array.from(normalizeInviteLabel(long)!)).toHaveLength(INVITE_LABEL_MAX_LENGTH)
  })

  it('fits the contract: the longest label seals within its 512 base64 characters', () => {
    const longest = '👵'.repeat(INVITE_LABEL_MAX_LENGTH)
    const sealed = sealInviteLabel(KEY, A, longest)
    expect(sealed.sealedLabel.length).toBeLessThanOrEqual(512)
    expect(sealed.wrappedLabelValueKey.length).toBeLessThanOrEqual(128)
  })
})
