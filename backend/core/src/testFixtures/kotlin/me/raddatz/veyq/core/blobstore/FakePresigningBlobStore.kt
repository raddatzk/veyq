package me.raddatz.veyq.core.blobstore

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.Collections

/**
 * Test-scope seam that makes `BlobUploadHandler`'s `verifyBlob`/`BlobVerify.Budget` path reachable at hermetic speed
 * (cr-media-budget-branch-coverage). `verifyBlob` short-circuits to `Ok` whenever `!blobStore.canPresign`
 * (`BlobUploadHandler.verifyBlob`), which is always true against [InMemoryBlobStore] — so the real-vs-declared `stat()`
 * comparison that decides `Budget` (`BlobUploadHandler.verifyBlob`) can never run in the default hermetic suite. This
 * store answers `canPresign = true` and means only that: it never carries real bytes, and its
 * `presignPut`/`presignGet` URLs are never dereferenced by anything that calls them here — the backend never
 * sees bytes either way (ADR 0013), only `stat()` results, which is the one method a test scripts.
 *
 * **Not a replacement for [InMemoryBlobStore] or [RecordingBlobStore] anywhere else.** It is a plain
 * `@Alternative` with no `@Priority`, so it never competes for the global `BlobStore` injection point —
 * [RecordingBlobStore] keeps winning that everywhere. It only activates where a `QuarkusTestProfile` names it
 * via `getEnabledAlternatives()`, which keeps the active config profile at `"test"` (unlike a `getConfigProfile`
 * override, which would silently drop `application-test.properties`) and touches nothing outside that one test
 * class.
 *
 * **It is mode-blind, and that is an oracle hole for anything about `veyq.blob-store.signing-mode`.** The
 * echo below reproduces exactly what [S3BlobStore.presignPut] does in
 * [me.raddatz.veyq.core.config.VeyqConfig.SigningMode.TENANT_DOMAIN] and *nothing* it does in the other two —
 * there is no endpoint here for a mode to resolve against. So a slice test driven through this store cannot
 * observe a signing mode at all: it answers the same host whatever the setting says, which means an assertion
 * about a mode would pass against an unimplemented one, and moving the config **default** changes nothing it
 * prints. Reach for a `@TestProfile` with real `veyq.blob-store.*` config instead — `RecordingBlobStore`'s
 * delegate is then the real [S3BlobStore] — which is what
 * [me.raddatz.veyq.memories.media.StoreOriginPresignSliceTest] does.
 *
 * **[stat] is scripted per key, not per test run.** [statOf] sets what a key answers; an unscripted key answers
 * `null` — "object missing", the same meaning [InMemoryBlobStore.stat] and a real absent S3 object share. A test
 * that wants `Budget` sets one declared chunk's stat either absent or larger than the session's declared
 * `byteSize`.
 */
@Alternative
@ApplicationScoped
class FakePresigningBlobStore : BlobStore {
    override val canPresign = true
    // Mode-blind like the rest of this store (KDoc): the echo below is tenant-domain signing, which is same-origin.
    override val storeOrigin: String? = null

    private val stats = Collections.synchronizedMap(HashMap<String, Long?>())

    /** Script what [stat] answers for [key]. Absent from this map ⇒ `null` (object missing). */
    fun statOf(key: String, size: Long?) {
        stats[key] = size
    }

    // The domain is echoed into the host so a test can assert *which* origin a URL was signed for without a
    // real S3 presigner; a null (the request resolved to no tenant) keeps the fixed bootstrap host.
    override fun presignPut(key: String, contentLength: Long, domain: String?): String = "https://${domain ?: "fake-presign.invalid"}/put/$key"
    override fun presignGet(key: String, domain: String?): String = "https://${domain ?: "fake-presign.invalid"}/get/$key"
    /**
     * Called with the key before [stat] answers; null (the default) does nothing. A completion stats its chunks after
     * its guest gate and before its attach transaction, so this is where a race test holds a guest complete between
     * the two (`GuestCompleteAfterDeleteRaceSliceTest`). The test that sets it clears it: one boot serves every class
     * of [me.raddatz.veyq.core.testing.FakePresignProfile].
     */
    @Volatile
    var onStat: ((String) -> Unit)? = null

    override fun stat(key: String): Long? {
        onStat?.invoke(key)
        return stats[key]
    }

    /** Unused on the budget path (`StoreReclaimSweep` is the only real deleter, ADR 0062 Decision 3). */
    override fun delete(keys: List<String>): List<String> = emptyList()
}
