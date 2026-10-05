package com.veyq.core.testing

import com.veyq.core.blobstore.FakePresigningBlobStore
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * The `@TestProfile` that swaps in [FakePresigningBlobStore] — `canPresign = true` with a scriptable `stat()`, so the
 * presign and declare-then-verify paths run in the hermetic suite. Every class that names it shares one Quarkus boot.
 */
class FakePresignProfile : QuarkusTestProfile {
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FakePresigningBlobStore::class.java)
}
