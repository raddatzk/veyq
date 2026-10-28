// Shared member-directory core for the tenant + group members panels (cr-0156, audit #74/P3d). Both panels
// repeated the same skeleton by copy — the member list + identity-trust state, the byte-identical `refreshTrust`,
// and the load path (on the cr-0155 `useAsyncData` + `handle401` primitives with
// the `loadErrorKind` enum). This is that skeleton, once. Each panel keeps its own divergent halves (Tenant:
// invite-by-email + K_tenant name-decrypt; Group: add-by-username + role/rotation/rename/lifecycle) and passes a
// `loadBody` that fetches its rows + resolves ownership; the composable owns everything the two share.
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ScopeMember } from './members'
import { trustStates, type ContactTrust } from '../account/identityPins'
import { TrustStateRefused, refusalMessageKey } from '../account/trustMarks'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { handle401 } from '../../shared/handle401'

/** What a panel's load fetches: its member rows + whether the caller owns this scope (derived panel-specifically). */
export interface MemberLoad {
  rows: ScopeMember[]
  isOwner: boolean
}

export function useMemberDirectory(loadBody: () => Promise<MemberLoad>) {
  const on401 = handle401(useRouter())

  const members = ref<ScopeMember[]>([])
  const isOwner = ref(false)
  // Identity trust (ADR 0028 Decision 3, cr-secure-the-account decision 9): per-member trust from the sealed pin store -
  // `verified` (compared on the member's profile) and `anomaly` (a served key the chain does not reach from the pinned
  // head) are shown in the list. A key the chain reached is no state of its own: it moves the pin. No review, no accept.
  const trust = ref<Record<string, ContactTrust>>({})
  // The i18n key naming why this device refused its own pin store (ADR 0028 Decision 7), or null. Every wrap to a
  // foreign key is blocked while it holds, so the panel says so rather than showing a directory with no flags.
  const trustRefusal = ref<string | null>(null)
  // Read every member's trust state in one fetch. Vault-gated: degrade to no flags rather than break the directory if
  // the store/identity is unavailable.
  async function refreshTrust() {
    try {
      trust.value = await trustStates(
        members.value.map((m) => ({ userId: m.userId, identityPublicKey: m.identityPublicKey, keyChainHead: m.keyChainHead })),
      )
      trustRefusal.value = null
    } catch (e) {
      trust.value = {}
      trustRefusal.value = e instanceof TrustStateRefused ? refusalMessageKey(e) : null
    }
  }

  const { pending: loading, error, reload } = useAsyncData(async () => {
    const loaded = await loadBody()
    members.value = loaded.rows
    isOwner.value = loaded.isOwner
    await refreshTrust()
  }, { onError: on401 })
  // The panels' shared load-error enum: 403 → in the scope but not a key-holder, else → failed, 401 → the
  // redirect handled it. (A non-member gets 404 → failed; see `loadErrorKind`.)
  const loadError = computed<'' | 'notAuthorized' | 'failed'>(() => loadErrorKind(error.value) ?? '')

  return { members, isOwner, trust, trustRefusal, loading, loadError, refreshTrust, reload, on401 }
}
