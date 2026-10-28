// A member's own client moves its name and picture forward to the tenant's current epoch (ADR 0023 § The epoch of a
// sealed name). The profile row records no epoch: a name or picture stays sealed under the tenant epoch current when its
// member saved it, and every reader opens it under every epoch key it holds, newest first (`unsealNameUnderEpochs`,
// `openAvatarUnderEpochs`). That alone keeps it readable after a rotation; this step makes it converge, the way ADR 0111
// decision 11 has a member's own client carry its writes forward:
//  - **on entering the tenant**, not on the next save: a name is set once and rarely saved again, so a "next write" may
//    never come, and every later rotation (every account a member secures rotates the tenant, ADR 0111 decision 8) would
//    add one failed trial open per reader and per member for good. Entering the tenant is where the device already holds
//    every epoch key, the current one included, and it happens on every load.
//  - **only the member's own row**: the profile is written by its member alone (`updateMyProfile`, `setMyAvatar` key the
//    row by the caller), and the plaintext is the one already sealed — opened and sealed again, nothing chosen.
//  - **a newer write wins**: the profile is read again right before the write, and the step writes only while the blob it
//    opened (the name) or the revision it opened (the picture) is still the stored one, so a save from another device in
//    between is not overwritten. The window left is the one round trip between that read and the write.
// Once per document and (tenant, member, epoch). Best-effort: a failure leaves the profile where it was (it still opens
// for every reader holding that epoch) and the next entry tries again.
import { tenantsApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { writeKey, type EpochKeys } from '../../spaces/epochs'
import { getMyProfile, setMyAvatar, updateMyProfile } from './profile'
import { sealName, unsealNameUnderEpochs } from './memberName'
import { openAvatarUnderEpochs, sealAvatar } from './memberAvatar'

/** The tenant as the entering device holds it: its id, its scope (the seal address) and every epoch key held. */
export interface HeldTenant {
  tenantId: string
  scopeId: string
  epochs: EpochKeys
}

/** What a run moved forward. */
export interface Carried {
  name: boolean
  picture: boolean
}

const done = new Set<string>()

/** Forget which (tenant, member, epoch) were carried this document (a sign-out, a test). */
export function forgetCarriedProfiles(): void {
  done.clear()
}

/**
 * Move the caller's own name and picture in `tenant` to its current epoch, when either opens only under an older one.
 * `userId` is the caller's own account id. `tenant.epochs` is read only during the call; the caller zeroes it afterwards.
 * Never rejects.
 */
export async function carryProfileForward(tenant: HeldTenant, userId: string): Promise<Carried> {
  const carried: Carried = { name: false, picture: false }
  const current = tenant.epochs.current
  // At epoch 0 nothing can be older; and each (tenant, member, epoch) once per document.
  const once = `${tenant.tenantId}:${userId}:${current}`
  if (current === 0 || !userId || done.has(once)) return carried
  done.add(once)
  try {
    const address = { scopeId: tenant.scopeId, userId }
    const key = writeKey(tenant.epochs)
    const profile = await getMyProfile()

    const name = unsealNameUnderEpochs(tenant.epochs, address, profile.sealedName)
    if (name && name.epoch < current) {
      const resealed = sealName(key, address, name.name)
      if ((await getMyProfile()).sealedName === profile.sealedName) {
        await updateMyProfile({ sealedName: resealed })
        carried.name = true
      }
    }

    if (profile.avatarRevision > 0) {
      const sealed = await withRefresh(() => tenantsApi().getMemberAvatar({ tenantId: tenant.tenantId, userId }))
      const picture = openAvatarUnderEpochs(tenant.epochs, address, sealed.sealedAvatar)
      if (picture && picture.epoch < current) {
        const resealed = sealAvatar(key, address, picture.bytes)
        if ((await getMyProfile()).avatarRevision === profile.avatarRevision) {
          await setMyAvatar(resealed)
          carried.picture = true
        }
      }
    }
  } catch {
    done.delete(once) // offline or refused: the next entry tries again
  }
  return carried
}
