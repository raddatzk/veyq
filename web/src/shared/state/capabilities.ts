import { reactive } from 'vue'
import type { Capability } from '../../api/generated'

// The `major` this web build was compiled against (ADR 0032): the platform's one major, the repository's VERSION
// at build time (ADR 0085 decision 1), the same file the server's build reads. The ONLY hard compatibility gate: if
// the instance advertises a different `major`, boot blocks with a direction-specific incompatibility screen BEFORE
// login. The web client ships in the server's own image, so it speaks exactly that one major (decision 6); it moves
// only when VERSION does, never by hand. `0` is pre-release.
export const EXPECTED_MAJOR: number = __VEYQ_MAJOR__

// Reactive capability state (uc.platform.capability-discovery): the instance level discovered from GET /api/setup on
// boot, and — once a space is open — its tenant's effective set (GET /api/tenants/{tenantId}/capabilities, ADR 0088
// decision 3), which narrows it. Both are identity-free (the same for every caller, for every member of the tenant),
// so they live in one module singleton the whole app soft-gates on, mirroring serverState.
export const capabilityState = reactive({
  // The instance's ACTIVE capability ids; null while boot is still discovering (features stay hidden until known).
  ids: null as string[] | null,
  // The open tenant's effective ids, a subset of `ids` (a tenant only narrows); null while no tenant's set is loaded,
  // and then the instance's ids are what the client reads.
  tenantIds: null as string[] | null,
  // Each active entry's requirement values in effect (ADR 0088 decision 8, `[{ id, …values }]`): the instance's from the
  // manifest, the tenant's (the strictest of both levels, server-computed) once its set is loaded.
  entries: {} as Record<string, Capability>,
  tenantEntries: null as Record<string, Capability> | null,
  // The instance's advertised version; null until discovered. `major` drives the hard gate, `build` is info-only.
  major: null as number | null,
  build: null as number | null,
})
