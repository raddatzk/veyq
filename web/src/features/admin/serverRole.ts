// Server-Owner role gate (web). There is no client-side serverRole yet (session.ts carries only tokens), and
// the backend never volunteers it — so we infer it from a Server-Owner-only call: listTenants() returns the
// list for an owner and 403 for anyone else. One idempotent probe per session sets `canManage`, which gates
// the ProfileMenu "Server settings" entry. The settings page re-checks via its own load (403 → not authorized),
// so a stale probe can never expose data — this only decides whether to *offer* the entry.
import { reactive } from 'vue'
import { listTenants } from './createTenant/tenants'

export const serverRole = reactive({ canManage: false, probed: false })

/** Probe Server-Owner authority once per session (idempotent). Safe to call from multiple mounts. */
export async function probeServerRole(): Promise<void> {
  if (serverRole.probed) return
  serverRole.probed = true
  try {
    await listTenants()
    serverRole.canManage = true
  } catch {
    // 403 (not the Server Owner) or 401 (no session) — keep the entry hidden.
    serverRole.canManage = false
  }
}

/** Reset the probe (used on sign-out so the next account re-probes). */
export function resetServerRole(): void {
  serverRole.canManage = false
  serverRole.probed = false
}
