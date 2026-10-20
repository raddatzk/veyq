// The client-facing capability ids the web client pins from the append-only catalog
// (`contracts/platform/capabilities.json`, ADR 0032, ADR 0088). A capability is instance-wide + pre-auth ("this server
// can do X at all"), discovered from GET /api/setup — NOT a per-scope/role permission. The client soft-gates
// each optional feature on the presence of its id (absent ⇒ hidden, graceful degradation). The ids are a tree, and the
// SERVER computes the AND along the path (ADR 0088 decision 2): a child whose parent is off is absent from the manifest.
// So the client reads the leaf it has UI for and never ANDs two ids itself (decision 3). A conformance test asserts
// each one is a catalog entry (drift-proofing — the same guard the crypto vectors + task tags get).

/** Public share links in the READ access mode, of any shareable node (uc.sharing.share-public-viewer). */
export const CORE_PUBLIC_SHARES = 'core.public-shares'

/** Public share links in the READ_WRITE access mode — the contribute role (uc.sharing.share-contributor). */
export const CORE_PUBLIC_SHARES_CONTRIBUTE = 'core.public-shares.contribute'

/** Public share links in the WRITE_ONLY access mode — a drop box, and creating Depot's drop-box node
 *  (uc.sharing.share-uploader). */
export const CORE_PUBLIC_SHARES_DROP_BOX = 'core.public-shares.drop-box'

/** Shared group spaces — creating a group (uc.spaces.* group use-cases; an existing group stays usable when off). */
export const CORE_GROUP_SPACE = 'core.group-space'

/** Remembering a device with a password DEVICE wrap (uc.account.trust-device). */
export const CORE_REMEMBER_DEVICE = 'core.remember-device'

/** Passwordless unlock on a remembered device — a DEVICE_KEY wrap (uc.account.remember-this-device). */
export const CORE_REMEMBER_DEVICE_PASSWORDLESS = 'core.remember-device.passwordless'

/** Linking a device from one already unlocked, every /api/pairing/* endpoint (uc.account.onboard-device, ADR 0059). */
export const CORE_DEVICE_LINKING = 'core.device-linking'

/** The breached-password check of a chosen password, an outside service (ADR 0088 decision 7): active only while the
 *  Server Owner configured its address, which the entry carries as `serviceUrl`. */
export const CORE_BREACHED_PASSWORD = 'core.breached-password'

/** Place names from a geocoder, an outside service; its address is the entry's `serviceUrl` (ADR 0016). */
export const MEMORIES_PLACES = 'memories.places'

/** Map tiles, an outside service; its URL template and attribution are the entry's `serviceUrl` and
 *  `serviceAttribution` (ADR 0018). */
export const MEMORIES_MAP = 'memories.map'
