import { reactive } from 'vue'

// The `major` version this web build was compiled against (ADR 0032). The ONLY hard compatibility gate: if the
// instance advertises a different `major`, boot blocks with a direction-specific incompatibility screen BEFORE
// login. Bump this ONLY in lockstep with the server, when the client drops reliance on a removed capability;
// everything additive (a new capability/field) never touches it.
export const EXPECTED_MAJOR = 1

// Reactive capability state discovered from GET /api/setup on boot (uc.platform.capability-discovery). It is
// instance-wide + identity-free — the same for every caller — so it lives in one module singleton the whole app
// soft-gates on, mirroring serverState.
export const capabilityState = reactive({
  // The instance's ACTIVE capability ids; null while boot is still discovering (features stay hidden until known).
  ids: null as string[] | null,
  // The instance's advertised version; null until discovered. `major` drives the hard gate, `build` is info-only.
  major: null as number | null,
  build: null as number | null,
})
