// Server-Owner role probe (web). There is no client-side serverRole (session.ts carries only tokens), and the
// backend never volunteers it — so we infer it from a Server-Owner-only call: `listTenants()` returns the list
// for an owner and 403 for anyone else. One idempotent probe per session sets `canManage`.
//
// Since the admin surface was extracted (ADR 0065) this decides ONE thing: whether to tell the signed-in
// account that server administration exists and lives elsewhere. There is nothing here for it to unlock — the
// tenant CRUD it used to gate now lives in `admin-web`, on its own origin, behind the same backend 403. So a
// stale probe cannot expose anything; at worst it offers or withholds a signpost.
//
// The call goes straight to the generated client rather than through a facade: `web/` no longer owns a
// Server-Owner data layer, and re-introducing a one-function one would read like the beginning of a second
// admin surface. `TermsAcceptView`'s sign-out path resets it (see `resetServerRole`).
import { reactive } from 'vue'
import { tenantsApi } from '../api/client'

export const serverRole = reactive({ canManage: false, probed: false })

/** Probe Server-Owner authority once per session (idempotent). Safe to call from multiple mounts. */
export async function probeServerRole(): Promise<void> {
  if (serverRole.probed) return
  serverRole.probed = true
  try {
    await tenantsApi().listTenants()
    serverRole.canManage = true
  } catch {
    // 403 (not the Server Owner) or 401 (no session) — keep the signpost hidden.
    serverRole.canManage = false
  }
}

/** Reset the probe (used on sign-out so the next account re-probes). */
export function resetServerRole(): void {
  serverRole.canManage = false
  serverRole.probed = false
}
