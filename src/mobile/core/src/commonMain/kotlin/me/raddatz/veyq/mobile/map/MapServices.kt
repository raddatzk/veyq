package me.raddatz.veyq.mobile.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.TenantSession
import kotlin.coroutines.CoroutineContext

/**
 * What a tenant's server serves of the map services (ADR 0018, ADR 0088 decision 7): the tile template and its
 * attribution (`memories.map`), the geocoder's base (`memories.places`) — each present only while configured and switched
 * on, for the server and the space — and whether the capability set was [read] at all (not offline).
 */
data class MapOffer(val tileTemplate: String?, val attribution: String?, val geocoder: String?, val read: Boolean = true)

/** Who fetches the map a platform draws: the operator's tile host (Android, Linux, Windows) or Apple Maps (MapKit). */
enum class TileRecipient { TILE_HOST, APPLE_MAPS }

/** What a map's place shows (ADR 0143 D11). */
sealed interface MapState {
    /** No map is served (switched off by the operator or the space's owner, or not configured): no map, on Apple too. */
    data object NotOffered : MapState

    /** Offline — the capabilities were not read, or the account lost its server since: which hosts are served now is
     *  unknown, so no map (D9). */
    data object Offline : MapState

    /** This device's consent is asked for [hosts]; [renewal] where an earlier one named other hosts. */
    data class Consent(val hosts: ConsentHosts, val renewal: Boolean) : MapState

    /** The map is shown: the consent holds for the hosts served now. */
    data object Shown : MapState
}

/**
 * One signed-in tenant's map services (ADR 0016, ADR 0018, ADR 0143 D2): what its server [offer]s, this device's
 * [consent] for the hosts served now, the [tiles] (where the core fetches them) and the [geocoder] — the port of the
 * web's `useMapConfig`. Nothing is requested before the consent holds for exactly the hosts it names, and nothing after a
 * withdrawal: the consent's change reaches every [MapServices] of the process at once, which drops its tiles, cancels
 * what is in flight and clears the geocoder's queue. [close] does the same and ends it (a lock, a sign-out, a tenant
 * switch).
 *
 * Offline no map is drawn (D9): where the capabilities were not read, and from the moment the account's [reach] loses
 * its server until it is back — the consent holds again then, unasked.
 *
 * **On Apple the tiles' recipient is Apple Maps** ([TileRecipient.APPLE_MAPS]): the consent names [APPLE_MAPS] in place
 * of the tile host, MapKit fetches its own map, and the core's [state] is what mounts it — `memories.map` off means no
 * map there too (owner, 2026-11-16). The geocoder is the operator's on every platform.
 */
class MapServices(
    private val offer: MapOffer,
    private val consent: MapConsent,
    http: MapHttp,
    val recipient: TileRecipient = TileRecipient.TILE_HOST,
    private val reach: StateFlow<Reach> = MutableStateFlow(Reach.Online),
    now: () -> Long = ::wallClockMs,
    context: CoroutineContext = Dispatchers.Default,
    /** Where the tiles outlive the session (the account's offline store, inbox #939), or none: memory only. */
    private val shelf: TileShelf? = null,
) {
    /** Whether the server serves a map at all (the consent is asked only then). */
    val offered: Boolean = offer.read && !offer.tileTemplate.isNullOrBlank()

    /** The tile provider's attribution, shown beside every map the core draws (MapKit shows Apple's own). */
    val attribution: String? = offer.attribution?.takeIf { offered && recipient == TileRecipient.TILE_HOST }

    /** The recipients a consent given now names: the tile host (or Apple Maps) and the geocoder host, null where not served. */
    val currentHosts: ConsentHosts = ConsentHosts(
        tiles = if (!offered) null else if (recipient == TileRecipient.APPLE_MAPS) APPLE_MAPS else serviceHost(offer.tileTemplate!!),
        places = offer.geocoder?.takeIf { offer.read && it.isNotBlank() }?.let(::serviceHost),
    )

    private val _state = MutableStateFlow(compute())

    /** What a map's place shows now; it moves at once with the consent. */
    val state: StateFlow<MapState> = _state.asStateFlow()

    /** Whether maps are shown: offered, and the consent holds for the hosts served now. */
    val available: Boolean get() = _state.value == MapState.Shown

    /** Whether place names are looked up: [available] and a geocoder served. */
    val geocodingAvailable: Boolean get() = available && currentHosts.places != null

    /** The hosts a renewed consent would add, where an earlier consent named others; null otherwise. */
    val renewal: ConsentHosts? get() = currentHosts.takeIf { consent.given.value != null && offered && !available }

    /** The hosts the stored consent was given for (null without one): what the settings name. */
    val consentGivenFor: ConsentHosts? get() = consent.given.value

    /** The tiles of the operator's map: null on Apple (MapKit fetches its own) and where no map is served. */
    val tiles: MapTiles? = if (offered && recipient == TileRecipient.TILE_HOST)
        MapTiles(http, offer.tileTemplate!!, gate = { available }, now = now, context = context, shelf = shelf)
    else null

    /** The operator's geocoder: null where none is served. */
    val geocoder: Geocoder? = offer.geocoder?.takeIf { currentHosts.places != null }
        ?.let { Geocoder(http, it, gate = { geocodingAvailable }, now = now, context = context) }

    private val stopObserving = consent.observe(::consentChanged)

    /** Follows the account's reach: offline mid-session the map goes, back online it returns (D9). */
    private val watch = CoroutineScope(SupervisorJob() + context).also { scope ->
        // the reach read at the start may have moved before this runs: every value is compared with what is shown
        scope.launch { reach.collect { if (compute() != _state.value) consentChanged() } }
    }

    init {
        shelve(_state.value)
    }

    /** Gives this device's consent for the hosts served now (the prompt's and the settings' *Turn on*). */
    fun turnOn() {
        if (offered) consent.give(currentHosts)
    }

    /** Withdraws it on this device (*Turn map services off*, on every map and in the settings): every map stops at once. */
    fun turnOff() = consent.withdraw()

    /** The tenant's map path ends — a lock, a sign-out, a tenant switch: the tiles dropped, the queue cleared, nothing asked again. */
    fun close() {
        stopObserving()
        watch.cancel()
        tiles?.close()
        geocoder?.clear()
    }

    /**
     * What the shelf may keep for this tenant now (inbox #939): where the map is shown, the tiles of the template served now
     * and no other; where it is not offered or the consent does not hold for the hosts served now, none — a withdrawal on
     * this device drops this account's at once, another account's at its next open. Offline nothing is dropped: offline is
     * no withdrawal, and nothing kept is read then (the gate).
     */
    private fun shelve(state: MapState) {
        val s = shelf ?: return
        val keep = when (state) {
            MapState.Offline -> return
            MapState.Shown -> offer.tileTemplate.takeIf { recipient == TileRecipient.TILE_HOST }
            else -> null
        }
        watch.launch { runCatching { s.drop(keep) } }
    }

    private fun consentChanged() {
        _state.value = compute()
        shelve(_state.value)
        if (!available) {
            tiles?.drop()
            geocoder?.clear()
        } else if (!geocodingAvailable) geocoder?.clear()
    }

    private fun compute(): MapState = when {
        !offer.read || reach.value !is Reach.Online -> MapState.Offline
        !offered -> MapState.NotOffered
        MapConsent.holds(consent.given.value, currentHosts) -> MapState.Shown
        else -> MapState.Consent(currentHosts, renewal = consent.given.value != null)
    }

    companion object {
        /** The tile capability: its `serviceUrl` the `{z}/{x}/{y}` template, its `serviceAttribution` the tiles'. */
        const val MAP = "memories.map"

        /** The geocoder capability: its `serviceUrl` a Nominatim-compatible base. */
        const val PLACES = "memories.places"

        /** How a consent on Apple names its map's recipient (the consent's `{tiles}`, owner question 3). */
        const val APPLE_MAPS = "Apple Maps"

        /** The map services of [tenant] on this device: its offer, the device's one consent, D3's door at its origin. */
        fun of(tenant: TenantSession, recipient: TileRecipient = TileRecipient.TILE_HOST): MapServices = MapServices(
            MapOffer(tenant.serviceUrl(MAP), tenant.serviceAttribution(MAP), tenant.serviceUrl(PLACES), tenant.servicesRead),
            MapConsent.of(tenant.account.deviceStore),
            MapHttp(tenant.account.outside, tenant.origin),
            recipient,
            tenant.account.reach,
            shelf = TileShelf.of({ tenant.account.offline }, tenant.tenant.tenantId),
        )
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun wallClockMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
