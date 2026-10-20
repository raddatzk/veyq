import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../core/people/directory', () => ({ loadScopePeople: vi.fn() }))
vi.mock('./mediaBuckets', async (orig) => ({ ...(await orig<typeof import('./mediaBuckets')>()), openBucketName: vi.fn() }))

import { useUploaderNames } from './useUploaderNames'
import { loadScopePeople } from '../../core/people/directory'
import { openBucketName, type BucketItem } from './mediaBuckets'
import type { BucketsDoor } from './source'
import type { MediaItem } from './media'
import { realI18n } from '../../test/i18n'

const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const GONE = 'dddddddd-0000-4000-8000-000000000004'
const bucket = (ownerKey: string, o: Partial<BucketItem> = {}): BucketItem => ({ bucketId: `b-${ownerKey}`, ownerKey, isPrimary: true, mediaCount: 1, ...o })
const item = (ownerKey?: string): MediaItem => ({ mediaId: 'm', original: { blobId: 'b', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'x', ownerId: 'm', role: 'ORIGINAL' }, derivatives: [], ownerKey })
const list = vi.fn<BucketsDoor['list']>()
const door = { list, ancestorKeys: () => [] } as unknown as BucketsDoor

function setup(buckets: BucketsDoor | null = door) {
  return useUploaderNames({
    scopeId: () => 's', nodeId: () => 'n', memoryKey: () => new Uint8Array(32), buckets: () => buckets,
    formerLabel: () => 'Former member', contributorLabel: () => 'Contributor',
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  realI18n() // the guest marker speaks the app's catalog, in English
  vi.mocked(loadScopePeople).mockResolvedValue([{ userId: ANNA, username: 'anna', realName: 'Anna Meyer', displayName: 'Anna Meyer' }])
  vi.mocked(openBucketName).mockReturnValue('Oma')
})

describe('useUploaderNames (ADR 0083 decision 7)', () => {
  it('a member who is still in the scope is named and linkable; one who left is a plain "Former member"', async () => {
    list.mockResolvedValue([bucket(`user:${ANNA}`), bucket(`user:${GONE}`)])
    const u = setup()
    await u.ensure()
    expect(u.uploaderOf(item(`user:${ANNA}`))).toEqual({ name: 'Anna Meyer', userId: ANNA })
    expect(u.uploaderOf(item(`user:${GONE}`))).toEqual({ name: 'Former member', userId: null })
  })

  it('a share contributor is its sealed display name WITHOUT a link, and "Contributor" when the name does not open', async () => {
    list.mockResolvedValue([bucket('share:i1', { shareId: 'sh' }), bucket('share:i2', { shareId: 'sh' })])
    vi.mocked(openBucketName).mockReturnValueOnce('Oma').mockReturnValueOnce(null)
    const u = setup()
    await u.ensure()
    expect(u.uploaderOf(item('share:i1'))).toEqual({ name: 'Oma (guest)', userId: null }) // marked as a guest's (inbox #433)
    expect(u.uploaderOf(item('share:i2'))).toEqual({ name: 'Contributor', userId: null }) // the fallback already says so: unmarked
  })

  it('a guest who took a member\'s name is marked; the member of that name is not (inbox #433)', async () => {
    list.mockResolvedValue([bucket(`user:${ANNA}`), bucket('share:i1', { shareId: 'sh' })])
    vi.mocked(openBucketName).mockReturnValue('Anna Meyer')
    const u = setup()
    await u.ensure()
    expect(u.uploaderOf(item(`user:${ANNA}`))).toEqual({ name: 'Anna Meyer', userId: ANNA })
    expect(u.uploaderOf(item('share:i1'))).toEqual({ name: 'Anna Meyer (guest)', userId: null })
  })

  it('a photo with no owner key, or an owner not listed, has no uploader; sub-buckets name nobody', async () => {
    list.mockResolvedValue([bucket(`user:${ANNA}`, { isPrimary: false })])
    const u = setup()
    await u.ensure()
    expect(u.uploaderOf(item())).toBeNull()
    expect(u.uploaderOf(item(`user:${ANNA}`))).toBeNull()
  })

  it('a session without the buckets door (a share guest) reads nothing and names nobody', async () => {
    const u = setup(null)
    await u.ensure()
    expect(list).not.toHaveBeenCalled()
    expect(loadScopePeople).not.toHaveBeenCalled()
    expect(u.uploaderOf(item(`user:${ANNA}`))).toBeNull()
  })

  it('reads once per node, and tries again at the next open after a failure', async () => {
    list.mockRejectedValueOnce(new Error('offline')).mockResolvedValue([bucket(`user:${ANNA}`)])
    const u = setup()
    await u.ensure()
    expect(u.uploaderOf(item(`user:${ANNA}`))).toBeNull()
    await u.ensure()
    await u.ensure()
    expect(list).toHaveBeenCalledTimes(2)
    expect(u.uploaderOf(item(`user:${ANNA}`))?.name).toBe('Anna Meyer')
  })

  it('still names a contributor when the member directory cannot be read', async () => {
    vi.mocked(loadScopePeople).mockRejectedValue(new Error('403'))
    list.mockResolvedValue([bucket(`user:${ANNA}`), bucket('share:i1', { shareId: 'sh' })])
    const u = setup()
    await u.ensure()
    expect(u.uploaderOf(item('share:i1'))?.name).toBe('Oma (guest)')
    expect(u.uploaderOf(item(`user:${ANNA}`))).toBeNull() // not claimed to have left: the directory is unknown
  })
})
