// The client-facing capability ids the web client pins from the append-only catalog
// (`contracts/platform/capabilities.json`, ADR 0032). A capability is instance-wide + pre-auth ("this server
// can do X at all"), discovered from GET /api/setup — NOT a per-scope/role permission. The client soft-gates
// each optional feature on the presence of its id (absent ⇒ hidden, graceful degradation). A conformance test
// asserts these equal the catalog (drift-proofing — the same guard the crypto vectors + task tags get).

/** Public read/contribute share links (viewer + contributor) of a personal-scope Memory (uc.sharing.*). */
export const SHARING_PUBLIC_LINKS = 'sharing.public-links'

/** Write-only uploader (drop-box) share links (uc.sharing.share-uploader). */
export const SHARING_UPLOADER_LINKS = 'sharing.uploader-links'

/** Shared group spaces — create a group + manage its membership (uc.spaces.* group use-cases). */
export const SPACES_GROUPS = 'spaces.groups'

/** Device trust / passwordless remember-me unlock levels (uc.account.* device use-cases). */
export const ACCOUNT_DEVICE_TRUST = 'account.device-trust'
