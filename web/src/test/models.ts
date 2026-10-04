// Fixture builders for the generated models — and the domain types built from them — that more than one test file
// serves (styleguide §10: one fixture per surface). Each returns the WHOLE model, annotated with its type, so a
// contract change that adds a required field or renames one turns the builder red here — once — instead of leaving a
// dozen hand-written partial literals green behind a cast. A test passes only the fields it is about: `memberProfile({ username: 'owner' })`. The overrides are
// a fresh literal, so a field the model does not have is still refused (TS2353).
//
// A builder only one file uses stays in that file.
import type { SetupStatus } from '../api/generated'
import type { AccountKeyMaterial, MemberProfile } from '../api/generated/account'
import type { MyTenant } from '../api/generated/scopes'
import type { InvitePreview } from '../api/generated/tenants'
import { NodeKind } from '../api/generated/content'
import type { MediaItem } from '../features/memory/media'
import type { TreeNode } from '../core/node/tree'

export const setupStatus = (o: Partial<SetupStatus> = {}): SetupStatus => ({
  setupRequired: false,
  version: { major: 1, build: 0 },
  capabilities: [],
  suites: ['v1'],
  instanceSalt: 'AAAAAAAAAAAAAAAAAAAAAA==',
  ...o,
})

export const memberProfile = (o: Partial<MemberProfile> = {}): MemberProfile => ({
  username: 'me',
  sealedName: null,
  requireGroupAddConsent: false,
  ...o,
})

export const myTenant = (o: Partial<MyTenant> = {}): MyTenant => ({
  tenantId: 'tenant-1',
  name: 'Familie Muster',
  domain: 'veyq.localhost',
  role: 'OWNER',
  status: 'ACTIVE',
  iconEpoch: 0,
  ...o,
})

export const invitePreview = (o: Partial<InvitePreview> = {}): InvitePreview => ({
  tenantId: 'tenant-1',
  tenantName: 'Familie Muster',
  domain: 'veyq.localhost',
  ownerEmail: 'owner@example.com',
  hasAccount: false,
  role: 'OWNER',
  ...o,
})

/** Key material whose wraps a test supplies when it opens one; the defaults open nothing. */
export const accountKeyMaterial = (o: Partial<AccountKeyMaterial> = {}): AccountKeyMaterial => ({
  userId: 'user-1',
  suiteId: 'v1',
  wraps: [],
  wrappedIdentityPrivateKey: 'AQ==',
  ...o,
})

/** A whole `MediaItem` (the domain type `media.ts` builds from a listed media) with one ORIGINAL tier. */
export const mediaItem = (o: Partial<MediaItem> & Pick<MediaItem, 'mediaId'>): MediaItem => ({
  original: { blobId: `blob-${o.mediaId}`, chunkCount: 1, byteSize: 3, wrappedBlobKey: 'k', ownerId: 'user-1', role: 'ORIGINAL' },
  derivatives: [],
  ...o,
})

/** A whole `TreeNode` (the domain type `tree.ts` builds from a node summary): an open Memory at the root. */
export const treeNode = (o: Partial<TreeNode> = {}): TreeNode => ({
  id: 'node-1',
  parentId: null,
  kind: NodeKind.Memory,
  orderIndex: 0,
  epoch: 0,
  name: 'n',
  wrappedContentKey: null,
  wrappedMeta: null,
  metaVersion: 0,
  ...o,
})
