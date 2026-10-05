package me.raddatz.veyq.core.platform.capabilities

/**
 * The client-facing capability ids the backend pins from the append-only catalog
 * (`contracts/platform/capabilities.json`, ADR 0032). A capability is **instance-wide + pre-auth** ("this
 * server can do X at all") — not a per-scope/role permission. Each id is advertised on `GET /api/setup` (minus
 * the operator's disabled list) and enforced at its feature's create/enable gate (`422 capability_disabled`).
 * [CapabilityRegistryTest] asserts every id here is a real catalog entry — the same drift-proofing
 * [me.raddatz.veyq.core.tasks.CapabilityTags] gets against its registry.
 */
object CapabilityIds {
    /** Public read/contribute share links (viewer + contributor) of a personal-scope Memory (uc.sharing.*). */
    const val SHARING_PUBLIC_LINKS = "sharing.public-links"

    /** Write-only uploader (drop-box) share links (uc.sharing.share-uploader). */
    const val SHARING_UPLOADER_LINKS = "sharing.uploader-links"

    /** Shared group spaces — create a group + manage its membership (uc.spaces.* group use-cases). */
    const val SPACES_GROUPS = "spaces.groups"

    /** Device trust / passwordless remember-me unlock levels (uc.account.* device use-cases). */
    const val ACCOUNT_DEVICE_TRUST = "account.device-trust"

    /**
     * The ids this build advertises as active — their serving use-cases are built (ADR 0032 §6). The manifest
     * is this list minus the operator's `veyq.capabilities.disabled` set, in a stable order. A catalog id whose
     * feature is not yet built would be *reserved* (present in the catalog, absent here) until it ships.
     */
    val ADVERTISED: List<String> = listOf(
        SHARING_PUBLIC_LINKS,
        SHARING_UPLOADER_LINKS,
        SPACES_GROUPS,
        ACCOUNT_DEVICE_TRUST,
    )
}
