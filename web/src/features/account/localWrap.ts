// "Which convenience wrap does THIS device hold?" — answered from local records only.
//
// One device carries at most one convenience tier (ADR 0005 §9): either a password DEVICE wrap (trust) or a
// password-less DEVICE_KEY wrap (remember-me). Both keep their server wrap id in a device-local record, and that
// record is the only place this client can *verify* the answer.
//
// It matters that this never asks the register and never asks the server:
//   - not the register, because the register is a naming layer and must stay off every unlock path, in both
//     directions (en.account.device-register § Quality notes);
//   - not the server, because the two callers are exactly the places where treating the server's list as
//     authority over the user's own key material would be the mistake — write moment 1 re-asserts the join from
//     local truth, and the "remove every saved key except this device's" action is defined by *excluding* what
//     this client can verify locally, never by a claim about the others (ADR 0058 §5).
import * as device from './trustDevice/deviceKey'
import { rememberedWrapId } from './rememberMe/deviceKeyWrap'

/**
 * This device's own convenience wrap id for `email`, or null when it holds none (a floor-only device, or one
 * whose storage is unavailable). Never throws — an unreadable local store answers "none", which for both callers
 * is the safe answer: no join row is written, and the exclusion-shaped bulk action excludes nothing.
 */
export async function localWrapId(email: string): Promise<string | null> {
  if (!email) return null
  const trust = await device.loadTrust(email).catch(() => null)
  if (trust) return trust.wrapId
  return await rememberedWrapId(email).catch(() => null)
}
