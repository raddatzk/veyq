// uc.account.sessions § Devices with a saved key — the data layer for the *convenience wraps* half of the
// sessions view: which device-local saved keys the server still holds for this account, and removing one.
//
// A thin wrapper over the generated account client, so the panel mocks this module rather than the HTTP runtime.
//
// **Why it filters to the two device methods.** The endpoint serves every *interactive* wrap, PASSWORD floor
// included (the RECOVERY wrap is filtered out server-side, so this list is never "all your unlock methods");
// the backend's DELETE whitelists only DEVICE and DEVICE_KEY, and *that whitelist is the guarantee*
// (KeyMaterialStore.deleteWrap). The filter here is for honesty — so the client never counts, shows or aims an
// action at a wrap it has no business removing — not for safety. A client-side filter is never the guarantee.
//
// **What this list is NOT.** It is the server's account of which wraps exist, and it is deliberately never
// presented to the user as something to act on wholesale ("these N wraps are recognised by no device — delete
// them?"). The wrap table has no timestamps, so nothing here can tell a genuine orphan from a device that has
// simply not signed in since the join shipped; treating the server's list as authority over the user's own key
// material is the reflex the operator-blind model forbids (ADR 0058 §5). Its two legitimate uses are: the
// authoritative set the register's wrap rows are reconciled against, and the set an *exclusion*-shaped action
// subtracts this device's own wrap from.
import { accountApi, accountWrapsApi } from '../../api/client'
import { MasterKeyWrapMethodEnum } from '../../api/generated/account'
import { withRefresh } from '../../app/sessionRefresh'

/** A device-local convenience wrap the server still serves. `method` is kept for copy, never for authorisation. */
export interface SavedKeyWrap {
  id: string
  method: MasterKeyWrapMethodEnum
}

const DEVICE_METHODS: ReadonlyArray<MasterKeyWrapMethodEnum> = [
  MasterKeyWrapMethodEnum.Device,
  MasterKeyWrapMethodEnum.DeviceKey,
]

/**
 * Every device-local convenience wrap the server currently serves for this account. Needs a **session, not an
 * unlock** — the wraps are ciphertext, and asking which exist is an auth-layer question.
 */
export async function listSavedKeyWraps(): Promise<SavedKeyWrap[]> {
  const material = await withRefresh(() => accountApi().getAccountKeyMaterial())
  return material.wraps
    .filter((w): w is typeof w & { id: string } => !!w.id && DEVICE_METHODS.includes(w.method))
    .map((w) => ({ id: w.id, method: w.method }))
}

/**
 * Remove one saved key server-side. Idempotent, and the server answers the same for an unknown or foreign id as
 * for a real one — so a failure here is a transport failure, never "that wrap did not exist".
 *
 * This reaches the *server's* copy only. Nothing here touches the key material on the device the wrap belonged
 * to; that device keeps an unusable handle until it drops it itself (ADR 0058 § Consequences).
 */
export function removeSavedKey(id: string): Promise<void> {
  return withRefresh(() => accountWrapsApi().deleteAccountWrap({ id }))
}
