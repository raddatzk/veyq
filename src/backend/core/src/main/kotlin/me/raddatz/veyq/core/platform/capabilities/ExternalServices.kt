package me.raddatz.veyq.core.platform.capabilities

import me.raddatz.veyq.core.appshell.ExternalOriginSet
import me.raddatz.veyq.core.appshell.ExternalOrigins
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/** One outside service's configured address (ADR 0088 decision 7): [attribution] for the map tiles only. */
data class ServiceAddress(val url: String, val attribution: String?)

/**
 * The addresses the Server Owner configured, by register entry (`CapabilityIds.EXTERNAL_SERVICES`). An entry with none
 * is not active ([CapabilityRegistry]). An interface so the registry's arithmetic is tested against a map handed in.
 */
fun interface ExternalServiceAddresses {
    fun configured(): Map<String, ServiceAddress>

    companion object {
        /** Every service configured with a placeholder address — for a test that is not about the services. */
        val ALL = ExternalServiceAddresses { CapabilityIds.EXTERNAL_SERVICES.associateWith { ServiceAddress("https://service.invalid/", null) } }
    }
}

/** One configured service; an unconfigured one has no row. The id is the register entry's, not a row id. */
@Entity
@Table(name = "veyq_external_service")
@DynamicUpdate
class ExternalServiceEntity : PanacheEntityBase {
    @Id
    @Column(name = "capability_id", length = 64)
    lateinit var capabilityId: String

    @Column(name = "url", nullable = false, length = 512)
    lateinit var url: String

    @Column(name = "attribution", length = 256)
    var attribution: String? = null

    companion object : PanacheCompanionBase<ExternalServiceEntity, String>
}

/** The services' persistence. Every method runs in the caller's transaction; one writer, the Server Owner. */
@ApplicationScoped
class ExternalServiceStore {
    fun all(): Map<String, ServiceAddress> =
        ExternalServiceEntity.listAll().associate { it.capabilityId to ServiceAddress(it.url, it.attribution) }

    /** Store [address] for [capabilityId], or remove it when null. */
    fun set(capabilityId: String, address: ServiceAddress?) {
        val row = ExternalServiceEntity.findById(capabilityId)
        when {
            address == null -> row?.delete()
            row == null -> ExternalServiceEntity().apply {
                this.capabilityId = capabilityId; url = address.url; attribution = address.attribution
            }.persist()
            else -> {
                row.url = address.url
                row.attribution = address.attribution
            }
        }
    }
}

/** The production [ExternalServiceAddresses]: read once and cached under a generation, the switches' pattern. */
@ApplicationScoped
class StoredExternalServiceAddresses(
    private val store: ExternalServiceStore,
) : ExternalServiceAddresses {
    private val generation = AtomicLong()

    @Volatile
    private var cached: Cached? = null

    override fun configured(): Map<String, ServiceAddress> {
        val current = generation.get()
        cached?.takeIf { it.generation == current }?.let { return it.addresses }
        return QuarkusTransaction.joiningExisting().call { store.all() }.also { cached = Cached(current, it) }
    }

    /** Forget the cached addresses. Call after the transaction that changed one has COMMITTED. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    private class Cached(val generation: Long, val addresses: Map<String, ServiceAddress>)
}

/**
 * The served policy's view of the services ([ExternalOrigins]): the origin of each service active at the instance level
 * — configured AND on, the AND along the path — as a snapshot. [refresh] recomputes it, at startup and after every write
 * that can change it (a service's address, an instance switch); [current] only reads it, because the shell asks on the
 * event loop. A tenant's own switch is not here: the policy is the instance's, and a tenant's members' clients leave a
 * service they do not see uncalled (ADR 0088 decision 7, uc.admin.configure-external-services A2).
 */
@ApplicationScoped
class ExternalServiceOrigins(
    private val registry: CapabilityRegistry,
    private val addresses: ExternalServiceAddresses,
    private val music: MusicDeployment,
) : ExternalOrigins {
    @Volatile
    private var snapshot: ExternalOriginSet = ExternalOriginSet.NONE

    override fun current(): ExternalOriginSet = snapshot

    fun onStart(@Observes event: StartupEvent) = refresh()

    /** Recompute the snapshot from the registry and the addresses. Call after a write has committed and its caches were
     *  invalidated, on a worker thread. */
    fun refresh() {
        snapshot = QuarkusTransaction.requiringNew().call {
            val configured = addresses.configured()
            fun originOf(id: String): String? =
                configured[id]?.takeIf { registry.isActive(id, null) }?.let { origin(it.url) }
            ExternalOriginSet(
                connect = CONNECT.mapNotNull(::originOf).distinct(),
                img = listOfNotNull(originOf(CapabilityIds.MEMORIES_MAP)).distinct(),
                // The music frame (ADR 0131): its host while the parent entry is active — configured and on. A token that
                // expires after this snapshot leaves the frame allowed until the next refresh: the frame is ours, and the
                // client stops embedding it at once (the entry leaves its set).
                frame = listOfNotNull(
                    music.settings.frameUrl?.takeIf { registry.isActive(CapabilityIds.MEMORIES_MUSIC, null) }?.let(::origin),
                ),
            )
        }
    }

    companion object {
        /**
         * The services the browser `fetch`es, in the order the policy lists their origins — written out, never derived
         * from the register, so a new external-service entry reaches `connect-src` only by being named here (the tiles
         * are images, `img-src`). The exchange rates are Expenses' (ADR 0108 decision 8), named by core as the geocoder
         * is Memories': the policy is one, the instance's.
         */
        val CONNECT = listOf(CapabilityIds.MEMORIES_PLACES, CapabilityIds.BREACHED_PASSWORD, CapabilityIds.EXPENSES_EXCHANGE_RATES)

        /** The `scheme://host[:port]` of an http(s) URL, or null — a `{z}` in a tile template is in the path only. */
        fun origin(url: String): String? = runCatching {
            val uri = URI(url.replace("{", "%7B").replace("}", "%7D"))
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) null
            else "${uri.scheme}://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}"
        }.getOrNull()
    }
}
