// Tiny in-memory hand-off so Setup can pre-fill the login form (email + Secret Key) without ever putting
// the Secret Key in the URL. Consumed once by the login view on mount, then cleared. (Login is a stub in
// this CR; the prefill is wired now so the real login view can read it unchanged.)
import { reactive } from 'vue'

export const authPrefill = reactive({ email: '', secretKey: '' })

export function setAuthPrefill(email: string, secretKey: string): void {
  authPrefill.email = email
  authPrefill.secretKey = secretKey
}

export function takeAuthPrefill(): { email: string; secretKey: string } | null {
  if (!authPrefill.email && !authPrefill.secretKey) return null
  const out = { email: authPrefill.email, secretKey: authPrefill.secretKey }
  authPrefill.email = ''
  authPrefill.secretKey = ''
  return out
}
