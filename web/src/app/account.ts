// The signed-in user's identity for display (cr-0031). In-memory only — the email is never written to storage
// (privacy). Set at login, read by the ProfileMenu for the avatar label + name, cleared on sign-out.
import { reactive } from 'vue'

export const account = reactive({ email: '' as string })

export function setAccount(email: string): void {
  account.email = email
}

export function clearAccount(): void {
  account.email = ''
}
