// The signed-in user's identity for display (cr-0031) and for keying per-user client settings. In-memory only — the
// email is never written to storage (privacy). Set at login, read by the ProfileMenu for the avatar label + name, gone
// with the document a sign-out loads. `userId` is the account's opaque id (from its own key material, set by every
// unlock path): the one thing a per-user setting in `localStorage` is keyed by (`shared/state/scopedPreference`), so two
// accounts on one browser never share a preference. Empty until the key material has been read.
import { reactive } from 'vue'

export const account = reactive({ email: '' as string, userId: '' as string })

export function setAccount(email: string): void {
  account.email = email
}

export function setAccountUserId(userId: string): void {
  account.userId = userId
}

export function clearAccount(): void {
  account.email = ''
  account.userId = ''
}
