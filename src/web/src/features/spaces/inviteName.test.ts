import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// The invitee is a real *foreign* identity. openInviteeName reads the caller's own identity from the vault, so we
// mock it to hand back a fresh copy of that identity's private bundle each call (the real vault does too —
// openInviteeName zeroes the copy it gets). A "locked" run returns null.
let vaultPriv: (() => ReturnType<typeof cloneRecipientPriv> | null) = () => null
vi.mock('../../app/vault', () => ({ identityPrivate: () => vaultPriv() }))

import { deriveGroupNameKey, wrapNameKeyToInvitee, openInviteeName, sealGroupName, openGroupNameWith } from './inviteName'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')

const recipient = v1.generateIdentity()
const recipientPubB64 = b64std(v1.serializeIdentityPublic(recipient.pub))
const GROUP = '5c09e000-0000-4000-8000-0000000000b1'
const OTHER_GROUP = '5c09e000-0000-4000-8000-0000000000b2'

// A fresh copy of the recipient's private bundle (openInviteeName wipes what the vault hands it).
function cloneRecipientPriv() {
  return {
    kemSeed: recipient.priv.kemSeed.slice(),
    sign: recipient.priv.sign.slice(),
  }
}

/** An invitation row as the server lists it: the scope it proposes, its name epoch, and the two ciphertexts. */
function invitation(epochKey: Uint8Array, name: string, opts: { scopeId?: string; nameEpoch?: number; to?: string } = {}) {
  const scopeId = opts.scopeId ?? GROUP
  const nameEpoch = opts.nameEpoch ?? 0
  const nameKey = deriveGroupNameKey(epochKey)
  return {
    scopeId,
    nameEpoch,
    encryptedName: sealGroupName(nameKey, scopeId, nameEpoch, name),
    sealedNameKey: wrapNameKeyToInvitee(nameKey, scopeId, nameEpoch, opts.to ?? recipientPubB64),
  }
}

beforeEach(() => {
  vaultPriv = () => null
})

afterEach(() => {
  vi.restoreAllMocks() // the zeroing test spies on v1.openAs; restore so it never leaks past its own test
})

describe('group invite name-key (cr-0096, ADR 0027)', () => {
  it('derives K_name deterministically from the epoch key, one-way (not equal to the epoch key)', () => {
    const epochKey = v1.randomBytes(32)
    const a = deriveGroupNameKey(epochKey)
    const b = deriveGroupNameKey(epochKey)
    expect(b64std(a)).toBe(b64std(b)) // deterministic
    expect(b64std(a)).not.toBe(b64std(epochKey)) // distinct from E_e (HKDF, not identity)
  })

  // Forward KAT — deriveGroupNameKey is deterministic HKDF (no nonce), so its output BYTES are pinnable. This is
  // what a round-trip cannot pin (both sides share the constant, so a mutated constant still round-trips): emptying
  // NAME_KEY_INFO (`veyq/v1/group-name-key` → '') changes the HKDF `info` → the output changes → this KAT reds.
  it('deriveGroupNameKey is a forward KAT — the HKDF info bytes are pinned to an absolute output', () => {
    const FIXED_EPOCH_KEY = Uint8Array.from(Array.from({ length: 32 }, (_, i) => i)) // 0x00..0x1f
    expect(hex(deriveGroupNameKey(FIXED_EPOCH_KEY)))
      .toBe('8677b0387c41736f45d07c62a383d7331a04490ccca74d6b063945a142524422')
  })

  it('wraps K_name to the invitee — only their identity private unwraps it, back to the same K_name', () => {
    const nameKey = deriveGroupNameKey(v1.randomBytes(32))
    const sealed = wrapNameKeyToInvitee(nameKey, GROUP, 2, recipientPubB64)

    // Opened with a HARDCODED context literal, not the module's own — so wrap (source) and open (this literal) are
    // independent: a mutated purpose or binding at the source makes this open refuse and the assertion red.
    const opened = v1.openAs(
      { purpose: 'group-name-key', scopeId: GROUP, nameEpoch: 2 },
      v1.hybridSecret(recipient.priv), fromB64std(sealed),
    )
    expect(b64std(opened)).toBe(b64std(nameKey))
  })

  it('is domain-separated from scope-grant and bound to its group + name epoch', () => {
    const sealed = fromB64std(wrapNameKeyToInvitee(deriveGroupNameKey(v1.randomBytes(32)), GROUP, 0, recipientPubB64))
    const sk = v1.hybridSecret(recipient.priv)
    // Pin the refusal, not a bare .toThrow(): each is the seal refusing the wrong address, not a structural error.
    expect(() => v1.openAs({ purpose: 'scope-grant', scopeId: GROUP, epoch: 0 }, sk, sealed)).toThrow(new v1.OpenRefused('scope-grant'))
    expect(() => v1.openAs({ purpose: 'group-name-key', scopeId: OTHER_GROUP, nameEpoch: 0 }, sk, sealed))
      .toThrow(new v1.OpenRefused('group-name-key'))
    expect(() => v1.openAs({ purpose: 'group-name-key', scopeId: GROUP, nameEpoch: 1 }, sk, sealed))
      .toThrow(new v1.OpenRefused('group-name-key'))
  })

  // The core ADR 0027 property: a pending invitee opens the SAME canonical `encryptedName` a member reads — there
  // is no separate owner-typed name copy, so a modified owner cannot privately show a divergent name.
  it('an invitee reads the same encryptedName as a member (no divergence)', () => {
    vaultPriv = cloneRecipientPriv
    const epochKey = v1.randomBytes(32) // E_e — the name's content-epoch key a member holds
    const name = 'Familie Raddatz'
    const inv = invitation(epochKey, name)

    // A member derives K_name from E_e and reads the canonical blob.
    const memberView = openGroupNameWith(deriveGroupNameKey(epochKey), GROUP, 0, inv.encryptedName)
    const inviteeView = openInviteeName(inv)

    expect(memberView).toBe(name)
    expect(inviteeView).toBe(name)
    expect(inviteeView).toBe(memberView) // identical — the invitee cannot be shown a different name
  })

  it("another group's name served on this invitation does not open — the invitee sees no name, not the wrong one", () => {
    vaultPriv = cloneRecipientPriv
    const epochKey = v1.randomBytes(32)
    const here = invitation(epochKey, 'Buchclub')
    const elsewhere = invitation(epochKey, 'Geheimclub', { scopeId: OTHER_GROUP })
    expect(openInviteeName({ ...here, encryptedName: elsewhere.encryptedName })).toBeNull()
    expect(openInviteeName({ ...here, sealedNameKey: elsewhere.sealedNameKey })).toBeNull()
    expect(openInviteeName(here)).toBe('Buchclub') // control
  })

  it('a name key a rotation cleared (ADR 0111 decision 9) reads as no name, and the vault is not even asked', () => {
    const asked = vi.fn(cloneRecipientPriv)
    vaultPriv = asked
    const here = invitation(v1.randomBytes(32), 'Buchclub')
    expect(openInviteeName({ ...here, sealedNameKey: null })).toBeNull()
    expect(asked).not.toHaveBeenCalled()
    expect(openInviteeName(here)).toBe('Buchclub') // control: the same invitation with its key opens
  })

  // cr-0089 class: an unasserted `finally` wipe silently rots. Removing the finally (BlockStatement → {}) must red.
  it('openInviteeName zeroes the name-key and the whole identity private bundle in finally', () => {
    const held = cloneRecipientPriv() // hand the SAME bundle back so we can inspect it after openInviteeName wipes it
    vaultPriv = () => held

    // Capture the exact buffer openAs returns — the internal `nameKey` the finally zeroes (spy calls through).
    let capturedNameKey: Uint8Array | null = null
    const realOpenAs = v1.openAs
    vi.spyOn(v1, 'openAs').mockImplementation((...args) => {
      const r = realOpenAs(...(args as Parameters<typeof realOpenAs>))
      capturedNameKey = r
      return r
    })

    const inv = invitation(v1.randomBytes(32), 'Wird gewischt')
    expect(openInviteeName(inv)).toBe('Wird gewischt') // succeeds → finally ran on success

    const allZero = (u: Uint8Array) => Array.from(u).every((byte) => byte === 0)
    expect(allZero(held.kemSeed)).toBe(true)
    expect(allZero(held.sign)).toBe(true)
    expect(capturedNameKey).not.toBeNull()
    expect(allZero(capturedNameKey!)).toBe(true)
  })

  it('openInviteeName returns null when the vault is locked', () => {
    vaultPriv = () => null
    expect(openInviteeName(invitation(v1.randomBytes(32), 'Buchclub'))).toBeNull()
  })

  it('openInviteeName returns null for a wrong-recipient / corrupt name-key (non-fatal)', () => {
    vaultPriv = cloneRecipientPriv
    // A name-key wrapped to a DIFFERENT identity won't open with the recipient's key → null, not a throw.
    const other = v1.generateIdentity()
    const inv = invitation(v1.randomBytes(32), 'Fremd', { to: b64std(v1.serializeIdentityPublic(other.pub)) })
    expect(openInviteeName(inv)).toBeNull()
  })
})
