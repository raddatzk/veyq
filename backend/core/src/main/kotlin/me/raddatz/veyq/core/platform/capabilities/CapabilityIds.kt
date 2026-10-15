package me.raddatz.veyq.core.platform.capabilities

/**
 * The client-facing capability ids the backend pins from the catalog (`contracts/platform/capabilities.json`,
 * ADR 0032, ADR 0088). A capability is **instance-wide + pre-auth** ("this server can do X at all") — not a
 * per-scope/role permission. The ids are a tree of dotted paths, and [CapabilityRegistry] ANDs each one with its
 * ancestor entries (ADR 0088 decision 2). Each id is advertised on `GET /api/setup` while it is active and enforced
 * at its feature's gate (`422 capability_disabled`). [CapabilityRegistryTest] asserts every id here is a real
 * catalog entry — the same drift-proofing [me.raddatz.veyq.core.tasks.CapabilityTags] gets against its registry.
 */
object CapabilityIds {
    /** Public share links in the READ mode, and the parent of every share entry (uc.sharing.share-public-viewer). */
    const val PUBLIC_SHARES = "core.public-shares"

    /** Public share links in the READ_WRITE mode (uc.sharing.share-contributor). */
    const val PUBLIC_SHARES_CONTRIBUTE = "core.public-shares.contribute"

    /** Public share links in the WRITE_ONLY mode, a drop box (uc.sharing.share-uploader). */
    const val PUBLIC_SHARES_DROP_BOX = "core.public-shares.drop-box"

    /** Shared group spaces; gated at creation only, a feature switch refuses only what is new (ADR 0088 decision 5). */
    const val GROUP_SPACE = "core.group-space"

    /** Remembering a device: enrolling a DEVICE wrap (uc.account.trust-device, uc.account.remember-this-device). */
    const val REMEMBER_DEVICE = "core.remember-device"

    /** Passwordless unlock on a remembered device: enrolling a DEVICE_KEY wrap (uc.account.remember-this-device). */
    const val REMEMBER_DEVICE_PASSWORDLESS = "core.remember-device.passwordless"

    /** Linking a device from an unlocked one: every `/api/pairing/` endpoint (uc.account.onboard-device, ADR 0059). */
    const val DEVICE_LINKING = "core.device-linking"

    /**
     * The ids this build advertises — their serving use-cases are built (ADR 0032 §6) — in the manifest's stable
     * order. The manifest is the active subset ([CapabilityRegistry.active]). A catalog id whose feature is not yet
     * built would be *reserved* (present in the catalog, absent here) until it ships.
     */
    val ADVERTISED: List<String> = listOf(
        PUBLIC_SHARES,
        PUBLIC_SHARES_CONTRIBUTE,
        PUBLIC_SHARES_DROP_BOX,
        GROUP_SPACE,
        REMEMBER_DEVICE,
        REMEMBER_DEVICE_PASSWORDLESS,
        DEVICE_LINKING,
    )
}
