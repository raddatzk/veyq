import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// The invitee is a real *foreign* identity. openInviteeName reads the caller's own identity from the vault, so we
// mock it to hand back a fresh copy of that identity's private bundle each call (the real vault does too —
// openInviteeName zeroes the copy it gets). A "locked" run returns null.
let vaultPriv: (() => ReturnType<typeof cloneRecipientPriv> | null) = () => null
vi.mock('../../app/vault', () => ({ identityPrivate: () => vaultPriv() }))

import { groupNameContext, wrapNameKeyToInvitee, openInviteeName, sealGroupName, openGroupNameWith } from './inviteName'
import { openValue, wrapValueKeyOf } from './valueKey'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

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

/** An invitation row as the server lists it: the scope it proposes, its name epoch, and the two ciphertexts — the name
 *  under a fresh value key (ADR 0115) and that key sealed to the invitee — plus the members' wrap of the same key under
 *  the scope key [epochKey] (`ScopeSummary.wrappedNameValueKey`), which the invitee is never served. */
function invitation(epochKey: Uint8Array, name: string, opts: { scopeId?: string; nameEpoch?: number; to?: string } = {}) {
  const scopeId = opts.scopeId ?? GROUP
  const nameEpoch = opts.nameEpoch ?? 0
  const nameKey = v1.generateValueKey()
  try {
    return {
      scopeId,
      nameEpoch,
      encryptedName: sealGroupName(nameKey, scopeId, nameEpoch, name),
      sealedNameKey: wrapNameKeyToInvitee(nameKey, scopeId, nameEpoch, opts.to ?? recipientPubB64),
      wrappedNameValueKey: wrapValueKeyOf({ epoch: nameEpoch, key: epochKey }, groupNameContext(scopeId, nameEpoch), nameKey),
    }
  } finally {
    nameKey.fill(0)
  }
}

/** A member's read of [inv]'s name: the value key from its wrap under the scope key, then the canonical name. */
const memberRead = (epochKey: Uint8Array, inv: ReturnType<typeof invitation>) =>
  openValue({ epoch: inv.nameEpoch, key: epochKey }, groupNameContext(inv.scopeId, inv.nameEpoch), inv.wrappedNameValueKey,
    (nameKey) => openGroupNameWith(nameKey, inv.scopeId, inv.nameEpoch, inv.encryptedName))

beforeEach(() => {
  vaultPriv = () => null
})

afterEach(() => {
  vi.restoreAllMocks() // the zeroing test spies on v1.openAs; restore so it never leaks past its own test
})

describe('group invite name-key (cr-0096, ADR 0027)', () => {
  // ADR 0115: the name key is the name's own value key, minted at every write — nothing derives it from the scope key, so
  // an invitee handed it learns the one name, and a holder of the scope key alone opens no name without its wrap.
  it('the name is under a value key of its own, not the scope key: the scope key alone does not open it', () => {
    const epochKey = v1.randomBytes(32)
    const a = invitation(epochKey, 'Buchclub')
    const b = invitation(epochKey, 'Buchclub')
    expect(memberRead(epochKey, a)).toBe('Buchclub') // control: through the wrap
    expect(() => openGroupNameWith(epochKey, GROUP, 0, a.encryptedName)).toThrow(new v1.OpenRefused('group-name'))
    // Two writes under the same scope key mint two keys: neither opens the other's name.
    expect(a.wrappedNameValueKey).not.toBe(b.wrappedNameValueKey)
    expect(() => openValue({ epoch: 0, key: epochKey }, groupNameContext(GROUP, 0), b.wrappedNameValueKey,
      (nameKey) => openGroupNameWith(nameKey, GROUP, 0, a.encryptedName))).toThrow(new v1.OpenRefused('group-name'))
  })

  it('wraps the name key to the invitee — only their identity private unwraps it, back to the same key', () => {
    const nameKey = v1.generateValueKey()
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
    const sealed = fromB64std(wrapNameKeyToInvitee(v1.generateValueKey(), GROUP, 0, recipientPubB64))
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
    const epochKey = v1.randomBytes(32) // the scope key a member holds
    const name = 'Familie Raddatz'
    const inv = invitation(epochKey, name)

    // A member opens the name's value key from its wrap under the scope key and reads the canonical blob.
    const memberView = memberRead(epochKey, inv)
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

  it('a name key a rename cleared (ADR 0111 decision 9) reads as no name, and the vault is not even asked', () => {
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
