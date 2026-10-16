package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.platform.capabilities.CapabilityEntryTexts
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilityTexts
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceOrigins
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceStore
import me.raddatz.veyq.core.platform.capabilities.ServiceAddress
import me.raddatz.veyq.core.platform.capabilities.StoredExternalServiceAddresses
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped

/** One external service as the Server Owner reads it: its register entry's words and its address, or none. */
data class ExternalServiceView(val id: String, val texts: CapabilityEntryTexts, val address: ServiceAddress?) {
    /** Only the map tiles carry an attribution (their providers ask for one on every map, ADR 0018). */
    val takesAttribution: Boolean get() = id == CapabilityIds.MEMORIES_MAP
}

sealed interface ExternalServiceOutcome {
    data class Stored(val view: ExternalServiceView) : ExternalServiceOutcome
    /** No external service has this register entry; nothing is stored. */
    data object NotFound : ExternalServiceOutcome, HttpFailure by httpFailure("service_not_found", FailureStatus.NOT_FOUND)
    /** Not an http(s) URL, a tile template without `{z}`, `{x}` or `{y}`, or an attribution for a service that takes
     *  none; nothing is stored. */
    data object Invalid : ExternalServiceOutcome, HttpFailure by Failures.invalidRequest
}

/**
 * The Server Owner's external services (uc.admin.configure-external-services, ADR 0088 decision 7, ADR 0089 decision 1):
 * what `/api/instance/external-services` does. A write is ordered as [CapabilitySwitchHandler.set]: commit, forget the
 * cached addresses, recompute the served policy's origins ([ExternalServiceOrigins.refresh]), read the service back — so
 * the next page a member loads gets the new address and a policy that allows exactly it.
 */
@ApplicationScoped
class ExternalServicesHandler(
    private val store: ExternalServiceStore,
    private val addresses: StoredExternalServiceAddresses,
    private val origins: ExternalServiceOrigins,
) {
    fun list(): List<ExternalServiceView> =
        addresses.configured().let { configured ->
            CapabilityIds.ADVERTISED.filter { it in CapabilityIds.EXTERNAL_SERVICES }.map { view(it, configured[it]) }
        }

    fun set(id: String, url: String, attribution: String?): ExternalServiceOutcome {
        if (id !in CapabilityIds.EXTERNAL_SERVICES) return ExternalServiceOutcome.NotFound
        val trimmed = attribution?.trim()?.ifEmpty { null }
        if (!acceptable(id, url, trimmed)) return ExternalServiceOutcome.Invalid
        return write(id, ServiceAddress(url, trimmed))
    }

    fun clear(id: String): ExternalServiceOutcome {
        if (id !in CapabilityIds.EXTERNAL_SERVICES) return ExternalServiceOutcome.NotFound
        return write(id, null)
    }

    private fun write(id: String, address: ServiceAddress?): ExternalServiceOutcome {
        QuarkusTransaction.requiringNew().run { store.set(id, address) }
        addresses.invalidate()
        origins.refresh()
        return ExternalServiceOutcome.Stored(view(id, addresses.configured()[id]))
    }

    private fun view(id: String, address: ServiceAddress?) = ExternalServiceView(id, CapabilityTexts.BY_ID.getValue(id), address)

    private fun acceptable(id: String, url: String, attribution: String?): Boolean {
        if (ExternalServiceOrigins.origin(url) == null) return false
        if (id == CapabilityIds.MEMORIES_MAP) return TILE_PLACEHOLDERS.all { it in url }
        return attribution == null
    }

    private companion object {
        val TILE_PLACEHOLDERS = listOf("{z}", "{x}", "{y}")
    }
}
