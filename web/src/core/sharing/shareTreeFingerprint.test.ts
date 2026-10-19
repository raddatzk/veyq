// The guest's tree fingerprint sees every served field the page reads from the head it holds (inbox #506): a poll whose
// only difference is one of them must not be dropped as "nothing changed". Each case has its control: the same share
// twice fingerprints equal.
import { describe, it, expect } from 'vitest'
import { shareTreeFingerprint } from './shareTreeFingerprint'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'

const ROOT = '6e5a0000-0000-4000-8000-000000000a01'
const FOLDER = '6e5a0000-0000-4000-8000-000000000a02'

function share(over: Partial<PublicShare> = {}, root: Partial<ShareNode> = {}, folder: Partial<ShareNode> = {}): PublicShare {
  return {
    role: ShareRole.ReadWrite,
    passwordRequired: false,
    rootKind: 'MEMORY',
    wrap: 'd3JhcA==',
    guestWriteRecordDays: 9,
    nodes: [
      { id: ROOT, kind: 'MEMORY', epoch: 0, blockedByOperator: false, ...root },
      { id: FOLDER, parentId: ROOT, kind: 'FOLDER', epoch: 0, encryptedName: 'bmFtZQ==', wrappedContentKey: 'a2V5', shareIdentityId: 'id-1', wrappedIdentityKey: 'a3Vw', blockedByOperator: false, ...folder },
    ],
    ...over,
  }
}

describe('shareTreeFingerprint', () => {
  it('is the same for the same share (the control)', () => {
    expect(shareTreeFingerprint(share())).toBe(shareTreeFingerprint(share()))
  })

  it("differs when only the guest-write record's N does: the notice before a first write must follow it", () => {
    expect(shareTreeFingerprint(share({ guestWriteRecordDays: 30 }))).not.toBe(shareTreeFingerprint(share()))
    expect(shareTreeFingerprint(share({ guestWriteRecordDays: null })), 'the switch turned off').not.toBe(shareTreeFingerprint(share()))
  })

  it("differs when only a node's drop key does: the first WRITE_ONLY link sets it and nothing else", () => {
    expect(shareTreeFingerprint(share({}, { sealedDropPrivateKey: 'ZHJvcA==' }))).not.toBe(shareTreeFingerprint(share()))
  })

  it("differs when only an identity folder's wrapped K_up does", () => {
    expect(shareTreeFingerprint(share({}, {}, { wrappedIdentityKey: 'b3RoZXI=' }))).not.toBe(shareTreeFingerprint(share()))
  })
})
