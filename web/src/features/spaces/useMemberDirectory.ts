// Shared member-directory core for the tenant + group members panels (cr-0156, audit #74/P3d). Both panels
// repeated the same skeleton by copy — the member list + identity-trust state, the byte-identical `refreshTrust`
// and changed-key review wiring, and the load path (on the cr-0155 `useAsyncData` + `handle401` primitives with
// the `loadErrorKind` enum). This is that skeleton, once. Each panel keeps its own divergent halves (Tenant:
// invite-by-email + K_tenant name-decrypt; Group: add-by-username + role/rotation/rename/lifecycle) and passes a
// `loadBody` that fetches its rows + resolves ownership; the composable owns everything the two share.
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ScopeMember } from './members'
import { trustStates, type TrustState } from '../account/identityPins'
import { admissionVerifies } from './grant'
import type { ScopeKind } from '../../api/generated/scopes'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { handle401 } from '../../shared/handle401'

/** What a panel's load fetches: its member rows + whether the caller owns this scope (derived panel-specifically). */
export interface MemberLoad {
  rows: ScopeMember[]
  isOwner: boolean
}

/** The key a panel's scope is admitted under: its current epoch key and that epoch. `release` zeroes a key the
 *  panel opened for this read (a key the active space owns is not released). */
export interface AdmissionKey {
  scopeId: string
  kind: ScopeKind
  key: Uint8Array
  epoch: number
  release?: () => void
}

export function useMemberDirectory(loadBody: () => Promise<MemberLoad>, admissionKey?: () => Promise<AdmissionKey | null>) {
  const on401 = handle401(useRouter())

  const members = ref<ScopeMember[]>([])
  const isOwner = ref(false)
  // Identity trust (cr-0099): per-member trust state from the sealed pin store. Only the `changed` state is
  // surfaced (a key differing from the pin — a rotation or a substitution); verified/recognized are intentionally
  // not shown (proactive verify is gated behind scope-wide strict-mode, cr-0100). Drives the review dialog.
  const trust = ref<Record<string, TrustState>>({})
  const reviewTarget = ref<ScopeMember | null>(null)
  const reviewOpen = ref(false)
  // Admission (ADR 0020 move 6): a pending member whose admission tag does not verify under this scope's current
  // epoch key is one no key-holder's worker will deliver to (it declines `admission_unverified`). Surfaced beside
  // the changed-key badge, the same way — the member would otherwise just sit "awaiting access" forever.
  const unadmitted = ref<Record<string, true>>({})

  // Read every member's trust state in one fetch — used only to flag `changed` keys. Vault-gated: degrade to no
  // flags rather than break the directory if the store/identity is unavailable.
  async function refreshTrust() {
    try {
      trust.value = await trustStates(members.value.map((m) => ({ userId: m.userId, directoryKey: m.identityPublicKey })))
    } catch {
      trust.value = {}
    }
  }

  // Verify every pending member's admission with the key this device holds — the check the worker runs before it
  // wraps. Degrades to no flags (never a broken directory) when the key cannot be had.
  async function refreshAdmission() {
    if (!admissionKey) return
    try {
      const held = await admissionKey()
      if (!held) {
        unadmitted.value = {}
        return
      }
      try {
        const out: Record<string, true> = {}
        for (const m of members.value) {
          if (m.hasGrant || m.pendingConsent) continue
          if (!admissionVerifies(held.key, held.epoch, held, m.userId, m)) out[m.userId] = true
        }
        unadmitted.value = out
      } finally {
        held.release?.()
      }
    } catch {
      unadmitted.value = {}
    }
  }

  function openReview(member: ScopeMember) {
    reviewTarget.value = member
    reviewOpen.value = true
  }

  const { pending: loading, error, reload } = useAsyncData(async () => {
    const loaded = await loadBody()
    members.value = loaded.rows
    isOwner.value = loaded.isOwner
    await refreshTrust()
    await refreshAdmission()
  }, { onError: on401 })
  // The panels' shared load-error enum: 403 → in the scope but not a key-holder, else → failed, 401 → the
  // redirect handled it. (A non-member gets 404 → failed; see `loadErrorKind`.)
  const loadError = computed<'' | 'notAuthorized' | 'failed'>(() => loadErrorKind(error.value) ?? '')

  return { members, isOwner, trust, unadmitted, reviewTarget, reviewOpen, loading, loadError, refreshTrust, refreshAdmission, openReview, reload, on401 }
}
