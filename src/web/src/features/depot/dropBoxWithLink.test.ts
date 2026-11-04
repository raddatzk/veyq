// A folder's "Add files only" as Depot fills it (owner 2026-11-04, `createDropBoxWithLink`): ONE flow of the two member writes
// a drop box always took - its create under the folder (a member's, re-read and pulled forward on an epoch refusal) and its
// first WRITE_ONLY link, minted on the key the drop box was just born with, no label (the drop box's name is what its guest
// sees). Core's node write and link mint are stubbed at their modules; the real drop-key derivation is `dropBox.wire.test.ts`'s.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../core/node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../core/node/tree')>()),
  createContentNode: vi.fn(),
}))
vi.mock('../../core/sharing/manageShares', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../core/sharing/manageShares')>()),
  mintShare: vi.fn(),
}))

import { createDropBoxWithLink } from './dropBox'
import { createContentNode } from '../../core/node/tree'
import { mintShare, NO_NOTE } from '../../core/sharing/manageShares'
import { ShareRole } from '../../api/generated/sharing'
import { DROPBOX_KIND } from './kinds'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const EPOCHS = { epoch: 3, key: new Uint8Array(32).fill(1) }
const PARENT = { id: 'folder-1', key: new Uint8Array(32).fill(2), epoch: 4, scopeEpoch: 2 }
const BOX_KEY = new Uint8Array(32).fill(7)

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(createContentNode).mockResolvedValue({ nodeId: 'box-1', contentKey: BOX_KEY, epoch: 0, scopeEpoch: 3 })
  vi.mocked(mintShare).mockResolvedValue({ shareId: 'link-1', kShare: new Uint8Array(32) })
})

describe('createDropBoxWithLink', () => {
  it('creates the drop box named by the link under the folder, then mints its WRITE_ONLY link on the new box\'s key', async () => {
    const until = new Date('2026-12-31T23:59:59Z')
    await expect(createDropBoxWithLink(SCOPE, EPOCHS, PARENT, 'Einsendungen', 'a-long-password', until)).resolves.toEqual({
      shareId: 'link-1', kShare: expect.any(Uint8Array),
    })
    expect(createContentNode).toHaveBeenCalledWith(SCOPE, EPOCHS, PARENT, DROPBOX_KIND, 'Einsendungen', undefined, {
      reread: expect.any(Function), pull: expect.any(Function), epochs: expect.any(Function),
    })
    const [scopeId, nodeId, root, epochs, nodeName, role, expiresAt, note, label, password, writeOnly] = vi.mocked(mintShare).mock.calls[0]
    expect({ scopeId, nodeId, nodeName, role, expiresAt, note, label, password }).toEqual({
      scopeId: SCOPE, nodeId: 'box-1', nodeName: 'Einsendungen', role: ShareRole.WriteOnly, expiresAt: until, note: NO_NOTE, label: '',
      password: 'a-long-password',
    })
    expect(root).toEqual({ key: BOX_KEY, epoch: 0 })
    expect(root.key).toBe(BOX_KEY)
    expect(epochs).toBe(EPOCHS)
    expect(writeOnly?.dropFields, 'the drop box\'s drop key rides the link').toEqual(expect.any(Function))
  })

  it('at the space\'s top level creates at the scope root, and mints nothing when the create failed', async () => {
    vi.mocked(createContentNode).mockRejectedValueOnce(new Error('slug_taken'))
    await expect(createDropBoxWithLink(SCOPE, EPOCHS, null, 'Einsendungen')).rejects.toThrow('slug_taken')
    expect(vi.mocked(createContentNode).mock.calls[0][2]).toBeNull()
    expect(mintShare).not.toHaveBeenCalled()
  })
})
