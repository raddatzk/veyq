package com.veyq.app.shared.blobstore

import com.veyq.app.shared.config.VeyqConfig
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.util.Optional

/**
 * Fail-loud guard (ADR 0013 §1, cr-0105): a byte-path object store is a **hard requirement** of any real instance.
 * Outside the hermetic `%test` profile a blank `veyq.blob-store.endpoint` **aborts startup** — media is not an
 * optional feature, so an instance without a store is misconfigured, not gracefully degraded (there is no
 * client-facing `directTransport` flag to soft-gate on any more). `%test` keeps the metadata-only in-memory stub.
 */
@ApplicationScoped
class BlobStoreStartupCheck(
    config: VeyqConfig,
) {
    private val endpoint: Optional<String> = config.blobStore().endpoint()

    fun onStart(@Observes ev: StartupEvent) {
        check(hasStore(endpoint.orElse(""), LaunchMode.current())) { REQUIRED_MSG }
    }

    companion object {
        const val REQUIRED_MSG =
            "veyq.blob-store.endpoint is required outside the %test profile: media needs a byte path (ADR 0013). " +
                "Run the bundled store (docker compose --profile s3 up -d) and set veyq.blob-store.endpoint + creds."

        /** A configured store is mandatory unless we are in the hermetic `%test` profile (in-memory stub). */
        fun hasStore(endpoint: String, mode: LaunchMode): Boolean =
            mode == LaunchMode.TEST || endpoint.isNotBlank()
    }
}
