import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// The invitee is a real *foreign* identity. openInviteeName reads the caller's own identity from the vault, so we
// mock it to hand back a fresh copy of that identity's private bundle each call (the real vault does too —
// openInviteeName zeroes the copy it gets). A "locked" run returns null.
let vaultPriv: (() => ReturnType<typeof cloneRecipientPriv> | null) = () => null
vi.mock('../../app/vault', () => ({ identityPrivate: () => vaultPriv() }))

import { deriveGroupNameKey, wrapNameKeyToInvitee, openInviteeName, GROUP_NAME_KEY } from './inviteName'
import { sealName, openName } from '../memory/tree'
import { SCOPE_GRANT } from './grant'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')

const recipient = v1.generateIdentity()
const recipientPubB64 = b64std(v1.serializeIdentityPublic(recipient.pub))

// A fresh copy of the recipient's private bundle (openInviteeName wipes what the vault hands it).
function cloneRecipientPriv() {
  return {
    kemX: recipient.priv.kemX.slice(),
    mlkemSeed: recipient.priv.mlkemSeed.slice(),
    sign: recipient.priv.sign.slice(),
  }
}

beforeEach(() => {
  vaultPriv = () => null
})

afterEach(() => {
  vi.restoreAllMocks() // the zeroing test spies on v1.asymUnwrap; restore so it never leaks past its own test
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
    // Pin the wrap AAD's bytes directly: emptying GROUP_NAME_KEY (`group-name-key` → '') at source reds this.
    expect(GROUP_NAME_KEY).toBe('group-name-key')

    const epochKey = v1.randomBytes(32)
    const nameKey = deriveGroupNameKey(epochKey)
    const sealed = wrapNameKeyToInvitee(nameKey, recipientPubB64)

    // Cross-check with the HARDCODED literal, not the imported constant — so wrap (source constant) and open
    // (this literal) are independent. A mutated source constant makes wrap use '' while this opens with the real
    // AAD → mismatch → asymUnwrap throws → this assertion reds. The existing import-both-sides form was blind.
    const opened = v1.asymUnwrap(
      v1.hybridSecret(recipient.priv), v1.hybridPublic(recipient.priv), fromB64std(sealed), 'group-name-key',
    )
    expect(b64std(opened)).toBe(b64std(nameKey))
  })

  it('is domain-separated from scope-grant — a name-key wrap can never open as a key-grant', () => {
    const sealed = wrapNameKeyToInvitee(deriveGroupNameKey(v1.randomBytes(32)), recipientPubB64)
    // Pin the throw REASON, not a bare .toThrow(): the rejection must be the AEAD tag check refusing the wrong AAD
    // (domain separation actually enforced), not some incidental structural error (the decodeKdfParams lesson).
    expect(() =>
      v1.asymUnwrap(
        v1.hybridSecret(recipient.priv), v1.hybridPublic(recipient.priv), fromB64std(sealed), SCOPE_GRANT,
      ),
    ).toThrow(/invalid ghash tag/)
  })

  // The core ADR 0027 property: a pending invitee opens the SAME canonical `encryptedName` a member reads — there
  // is no separate owner-typed name copy, so a modified owner cannot privately show a divergent name.
  it('an invitee reads the same encryptedName as a member (no divergence)', () => {
    vaultPriv = cloneRecipientPriv
    const epochKey = v1.randomBytes(32) // E_e — the name's content-epoch key a member holds
    const name = 'Familie Raddatz'

    // What the owner/members store: the name sealed under K_name derived from E_e (the canonical `encryptedName`).
    const encryptedName = sealName(deriveGroupNameKey(epochKey), name)

    // A member derives K_name from E_e and reads the canonical blob.
    const memberView = openName(deriveGroupNameKey(epochKey), encryptedName)
    // The invitee is handed K_name (wrapped) and opens the SAME blob.
    const sealedNameKey = wrapNameKeyToInvitee(deriveGroupNameKey(epochKey), recipientPubB64)
    const inviteeView = openInviteeName(sealedNameKey, encryptedName)

    expect(memberView).toBe(name)
    expect(inviteeView).toBe(name)
    expect(inviteeView).toBe(memberView) // identical — the invitee cannot be shown a different name
  })

  // cr-0089 class: an unasserted `finally` wipe silently rots. Removing the finally (BlockStatement → {}) must red.
  it('openInviteeName zeroes the name-key and the whole identity private bundle in finally', () => {
    const held = cloneRecipientPriv() // hand the SAME bundle back so we can inspect it after openInviteeName wipes it
    vaultPriv = () => held

    // Capture the exact buffer asymUnwrap returns — the internal `nameKey` the finally zeroes (spy calls through).
    let capturedNameKey: Uint8Array | null = null
    const realUnwrap = v1.asymUnwrap
    vi.spyOn(v1, 'asymUnwrap').mockImplementation((...args) => {
      const r = realUnwrap(...(args as Parameters<typeof realUnwrap>))
      capturedNameKey = r
      return r
    })

    const epochKey = v1.randomBytes(32)
    const nameKey = deriveGroupNameKey(epochKey)
    const encryptedName = sealName(nameKey, 'Wird gewischt')
    const sealedNameKey = wrapNameKeyToInvitee(nameKey, recipientPubB64)

    expect(openInviteeName(sealedNameKey, encryptedName)).toBe('Wird gewischt') // succeeds → finally ran on success

    const allZero = (u: Uint8Array) => Array.from(u).every((byte) => byte === 0)
    expect(allZero(held.kemX)).toBe(true)
    expect(allZero(held.mlkemSeed)).toBe(true)
    expect(allZero(held.sign)).toBe(true)
    expect(capturedNameKey).not.toBeNull()
    expect(allZero(capturedNameKey!)).toBe(true)
  })

  it('openInviteeName returns null when the vault is locked', () => {
    vaultPriv = () => null
    const epochKey = v1.randomBytes(32)
    const encryptedName = sealName(deriveGroupNameKey(epochKey), 'Buchclub')
    const sealedNameKey = wrapNameKeyToInvitee(deriveGroupNameKey(epochKey), recipientPubB64)
    expect(openInviteeName(sealedNameKey, encryptedName)).toBeNull()
  })

  it('openInviteeName returns null for a wrong-recipient / corrupt name-key (non-fatal)', () => {
    vaultPriv = cloneRecipientPriv
    const epochKey = v1.randomBytes(32)
    const encryptedName = sealName(deriveGroupNameKey(epochKey), 'Fremd')
    // A name-key wrapped to a DIFFERENT identity won't open with the recipient's key → null, not a throw.
    const other = v1.generateIdentity()
    const sealedNameKey = wrapNameKeyToInvitee(deriveGroupNameKey(epochKey), b64std(v1.serializeIdentityPublic(other.pub)))
    expect(openInviteeName(sealedNameKey, encryptedName)).toBeNull()
  })
})
