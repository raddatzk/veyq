// Fixture builders for the generated models — and the domain types built from them — that more than one test file
// serves (styleguide §10: one fixture per surface). Each returns the WHOLE model, annotated with its type, so a
// contract change that adds a required field or renames one turns the builder red here — once — instead of leaving a
// dozen hand-written partial literals green behind a cast. A test passes only the fields it is about: `memberProfile({ username: 'owner' })`. The overrides are
// a fresh literal, so a field the model does not have is still refused (TS2353).
//
// A builder only one file uses stays in that file.
import type { SetupStatus } from '../api/generated'
import type { AccountKeyMaterial, MemberProfile } from '../api/generated/account'
import { ScopeKind, type MyTenant, type ScopeMember, type ScopeSummary } from '../api/generated/scopes'
import type { OpenedScope } from '../features/spaces/scopes'
import type { InvitePreview } from '../api/generated/tenants'
import { MEMORY_KIND } from '../features/memory/kinds'
import type { MediaItem } from '../features/memory/media'
import type { TreeNode } from '../core/node/tree'
import type { NodeKind } from '../core/node/kind'
import type { EpochKeys } from '../features/spaces/epochs'
import { memberAccess, memberTreeSource, type GuestAccess, type MemberAccess, type TreeSource } from '../core/access'
import type { ShareRole } from '../api/generated/sharing'
import type { PublicShareHead } from '../core/sharing/resolvePublicShare'
import type { GuestIdentity } from '../core/sharing/guestIdentity'
import { guestAccess } from '../core/sharing/guestAccess'
import { memoryCapabilities, type MemoryCapabilities } from '../features/memory/capabilities'

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
  avatarVersion: 0,
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
  kind: MEMORY_KIND,
  orderIndex: 0,
  epoch: 0,
  name: 'n',
  wrappedContentKey: 'eA==',
  wrappedMeta: null,
  metaVersion: 0,
  placementVersion: 0,
  createdAt: 0,
  ...o,
})

/** A member's `Access` (core/access.ts): an unlocked scope, not pending deletion, every public-share leaf on (READ,
 *  contribute, drop box) — a test passes only what it is about. */
export const memberAccessOf = (o: Partial<Omit<MemberAccess, 'mode'>> = {}): MemberAccess =>
  memberAccess({
    scopeId: 'scope-1', epochs: null, readonly: false,
    publicShares: true, publicSharesContribute: true, publicSharesDropBox: true,
    ...o,
  })

/** A share guest's `Access` with `role` (an unknown token too, as a server could send one): the head is a stub — a
 *  capability reads the role alone. */
export const guestAccessOf = (role: string, o: Partial<Omit<GuestAccess, 'mode' | 'role'>> = {}): GuestAccess => ({
  mode: 'guest',
  shareId: 's1',
  role: role as ShareRole,
  head: {} as PublicShareHead,
  fragmentKey: 'frag',
  requestGuest: async () => null,
  reload: async () => {},
  ...o,
})

/** The guest `Access` the share shell hands a body for `head` (`guestAccess`, core/sharing/guestAccess.ts): its role and
 *  password token from the head, and a naming flow — by default one that is never answered (a test that names a guest
 *  passes its own) — and a re-resolve that does nothing unless the test passes one. */
export const guestAccessFor = (
  head: PublicShareHead,
  o: { pwToken?: string; fragmentKey?: string; requestGuest?: () => Promise<GuestIdentity | null>; reload?: () => Promise<void> } = {},
): GuestAccess => guestAccess({
  head, pwToken: o.pwToken, fragmentKey: o.fragmentKey ?? 'frag',
  requestGuest: o.requestGuest ?? (() => new Promise(() => {})), reload: o.reload ?? (async () => {}),
})

/** A member's `TreeSource` over `nodes` (a getter, so a test's reactive store is read anew), keys opened under `epochs`. */
export const memberTreeOf = (
  nodes: () => TreeNode[],
  o: { scopeId?: string | null; epochs?: EpochKeys | null; scope?: string } = {},
): TreeSource<TreeNode> =>
  memberTreeSource({ nodes, scopeId: () => o.scopeId ?? 'scope-1', epochs: () => o.epochs ?? null, scope: () => o.scope ?? '~me' })

/** A member's Memories capabilities on an opened node of `kind` (MEMORY by default) — what `MemoryDetailHost` computes. */
export const memberMemoryCaps = (o: Partial<Omit<MemberAccess, 'mode'>> = {}, kind: NodeKind = MEMORY_KIND): MemoryCapabilities =>
  memoryCapabilities(memberAccessOf(o), { kind, inIdentityFolder: false, shareable: true })

export const scopeMember = (o: Partial<ScopeMember> = {}): ScopeMember => ({
  userId: 'user-1',
  username: 'anna',
  sealedName: null,
  avatarVersion: 0,
  role: 'MEMBER',
  identityPublicKey: 'AAAA',
  hasGrant: true,
  ...o,
})

/** An opened scope holding one epoch key (`key`, defaulting to 32 bytes of 7); a test may zero it and look. */
export const openedScope = (o: Partial<OpenedScope> = {}): OpenedScope => {
  const key = o.key ?? new Uint8Array(32).fill(7)
  return {
    scopeId: 'scope-1', tenantId: 'tenant-1', kind: ScopeKind.Tenant, key, epochs: { current: 0, keys: new Map([[0, key]]) },
    nameEpoch: 0, deletedAt: null, purgeAfter: null, ...o,
  }
}

export const scopeSummary = (o: Partial<ScopeSummary> = {}): ScopeSummary => ({
  id: 'scope-1', kind: ScopeKind.Tenant, role: 'OWNER', tenantId: 'tenant-1', nameEpoch: 0, iconEpoch: 0, currentEpoch: 0,
  grantedEpoch: 0, wrappedGrant: 'AAAA', ...o,
})
