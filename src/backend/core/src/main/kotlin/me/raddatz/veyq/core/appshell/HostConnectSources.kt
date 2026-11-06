package me.raddatz.veyq.core.appshell

/**
 * Sources a page's `connect-src` gains for the host it is served on (ADR 0121 decision 2): `https:` while the tenant whose
 * domain that is has `core.share-embedding` effective, so a kept link's page can read its share's own instance. The kernel
 * asks; the capability slice answers (`ShareEmbeddingHosts`), so the shell names no register entry. [forHost] must not
 * touch the database — the header is written on the event loop — so the answer is a snapshot, as [ExternalOrigins]' is.
 * [host] is `TenantHostResolver.hostOf`'s reading of the request, null when it names none.
 */
interface HostConnectSources {
    fun forHost(host: String?): List<String>
}
