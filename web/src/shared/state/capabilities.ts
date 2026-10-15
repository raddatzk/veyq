import { reactive } from 'vue'

// The `major` this web build was compiled against (ADR 0032): the platform's one major, the repository's VERSION
// at build time (ADR 0085 decision 1), the same file the server's build reads. The ONLY hard compatibility gate: if
// the instance advertises a different `major`, boot blocks with a direction-specific incompatibility screen BEFORE
// login. The web client ships in the server's own image, so it speaks exactly that one major (decision 6); it moves
// only when VERSION does, never by hand. `0` is pre-release.
export const EXPECTED_MAJOR: number = __VEYQ_MAJOR__

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
