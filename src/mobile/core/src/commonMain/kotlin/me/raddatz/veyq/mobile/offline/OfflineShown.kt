package me.raddatz.veyq.mobile.offline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.OfflineAsOf
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Moment
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice

/**
 * Where a pinned node's fetch is, as its screens show it (ADR 0140 decision 10): [OFF] not pinned, else the pin's
 * [Pin.State], [FETCHING] while its fetch runs in this process.
 */
@Serializable
enum class OfflineStatus { OFF, WAITING, FETCHING, KEPT, NO_SPACE, UNAVAILABLE }

/**
 * A node's "Available offline" toggle (ADR 0140 decisions 9 and 10), sizes only: whether it is [on], its [status] and
 * the line under it ([said], the core's words: "Downloading 3 MB of 12 MB", "On this device", "Paused: not enough space
 * on this device", "Not available right now"), the bytes fetched of the bytes its fetch set out to take while
 * [OfflineStatus.FETCHING] ([done], [total]); and while the person is asked to confirm a pin, the size it takes
 * ([asking], bytes) with the question ([question]). Turning it on asks first; turning it off does not.
 */
@Serializable
@SerialName("OfflineToggle")
data class OfflineToggle(
    val on: Boolean,
    val status: OfflineStatus,
    val said: Notice?,
    val done: Long? = null,
    val total: Long? = null,
    val asking: Long? = null,
    val question: Notice? = null,
)

/**
 * One pinned item on Settings' "Storage on this device" (ADR 0140 decision 10): its ids, its kind, its [title] — its name
 * from the tree the app holds (`TenantSession.trees`, never the store: it keeps no name), else a nameless word —, its
 * status with its line, and the bytes the store keeps that it reaches ([bytes]; an entry two pins reach counts in each).
 */
@Serializable
@SerialName("OfflineItem")
data class OfflineItem(
    val tenant: String,
    val scope: String,
    val node: String,
    val kind: String,
    val title: Notice,
    val status: OfflineStatus,
    val said: Notice?,
    val bytes: Long,
)

/** The unit of the Settings section's free size field: decimal, as the platforms' file sizes read. */
@Serializable
enum class LimitUnit(val bytes: Long, val word: Msg) { MB(1_000_000L, Msg.STORAGE_UNIT_MB), GB(1_000_000_000L, Msg.STORAGE_UNIT_GB) }

/**
 * The limit as the free size field shows it: an [amount] as typed ("2", "2.5" — "2,5" where the reader's decimal sign is
 * a comma —, "500") in a [unit]; [off] the tier is off.
 */
@Serializable
@SerialName("LimitField")
data class LimitField(val amount: String, val unit: LimitUnit, val off: Boolean)

/** The words and rules phase 5's screens show; the platforms only draw them (ADR 0136 decisions 1 and 3). */
object OfflineShown {
    /** The status of the pin of [node] among [pins], [progress] its fetch where one runs; [OfflineStatus.OFF] where none is. */
    fun status(node: String, pins: List<Pin>, progress: Map<String, OfflineFetching.Progress>): OfflineStatus {
        val pin = pins.firstOrNull { it.node == node } ?: return OfflineStatus.OFF
        if (progress[node] != null) return OfflineStatus.FETCHING
        return when (pin.state) {
            Pin.State.WAITING -> OfflineStatus.WAITING
            Pin.State.FETCHING -> OfflineStatus.FETCHING
            Pin.State.KEPT -> OfflineStatus.KEPT
            Pin.State.NO_SPACE -> OfflineStatus.NO_SPACE
            Pin.State.UNAVAILABLE -> OfflineStatus.UNAVAILABLE
        }
    }

    /** The line under a status ("Downloading {done} of {total}", …); null where it is off. */
    fun said(status: OfflineStatus, progress: OfflineFetching.Progress?): Notice? = when (status) {
        OfflineStatus.OFF -> null
        OfflineStatus.WAITING -> Notice(Msg.OFFLINE_STATUS_WAITING)
        OfflineStatus.FETCHING -> progress?.let { Notice(Msg.OFFLINE_STATUS_FETCHING, Bytes(it.done), Bytes(it.total)) } ?: Notice(Msg.OFFLINE_STATUS_WAITING)
        OfflineStatus.KEPT -> Notice(Msg.OFFLINE_STATUS_KEPT)
        OfflineStatus.NO_SPACE -> Notice(Msg.OFFLINE_STATUS_NO_SPACE)
        OfflineStatus.UNAVAILABLE -> Notice(Msg.OFFLINE_STATUS_UNAVAILABLE)
    }

    /** The toggle of [node] as [pins] and [progress] hold it, [asking] the size a pin waits to be confirmed with. */
    fun toggle(node: String, pins: List<Pin>, progress: Map<String, OfflineFetching.Progress>, asking: Long?): OfflineToggle {
        val status = status(node, pins, progress)
        val p = progress[node]
        return OfflineToggle(
            on = status != OfflineStatus.OFF, status = status, said = said(status, p),
            done = p?.done, total = p?.total, asking = asking, question = asking?.let(::ask),
        )
    }

    /** What a pin asks before its first byte: the size it keeps on this device. */
    fun ask(bytes: Long): Notice = Notice(Msg.OFFLINE_PIN_ASK, Bytes(bytes))

    /**
     * The shell's banner offline: "Offline — content as of {time}" ([asOf] known), else "Offline"; null online. [page]: the
     * open page's own kept time (a Pot's ledger, a gallery's listing, [PagesOffline]), which dates it where there is one —
     * the lists' time mis-dated an open Pot read before them.
     */
    fun banner(offline: OfflineAsOf?, page: Long? = null): Notice? = offline?.let { o ->
        (page ?: o.asOf)?.let { Notice(Msg.OFFLINE_BANNER, Moment(it)) } ?: Notice(Msg.OFFLINE_BANNER_UNKNOWN)
    }

    /** A pin dropped since its node is no longer available: named where the app still holds the name, else nameless. */
    fun gone(name: String?): Notice = name?.let { Notice(Msg.OFFLINE_GONE_NAMED, it) } ?: Notice(Msg.OFFLINE_GONE)

    /** A pinned item's title on Settings: its name where the app holds it, else nameless. */
    fun title(name: String?): Notice = name?.let { Notice(Msg.OFFLINE_ITEM_NAMED, it) } ?: Notice(Msg.OFFLINE_ITEM_UNNAMED)

    /**
     * The free size field's value of a limit of [bytes]: in GB from 1 GB up (a whole number, else one or two decimals,
     * written with [separator], the reader's decimal sign — a German UI reads "1,5"), in MB below; 0 is "Off" (the field
     * shows the default unit, empty). [parse] reads either sign back.
     */
    fun field(bytes: Long, separator: Char = '.'): LimitField {
        if (bytes <= 0) return LimitField("", LimitUnit.GB, off = true)
        val unit = if (bytes >= LimitUnit.GB.bytes) LimitUnit.GB else LimitUnit.MB
        return LimitField(decimal(bytes, unit.bytes, separator), unit, off = false)
    }

    /**
     * The limit [amount] in [unit] comes to, in bytes: a positive number, a point or a comma as the decimal sign, at most
     * two decimals counted; null for anything else (empty, negative, not a number, beyond 1,000,000 GB).
     */
    fun parse(amount: String, unit: LimitUnit): Long? {
        val t = amount.trim().replace(',', '.')
        if (!Regex("\\d{1,7}(\\.\\d{1,2})?").matches(t)) return null
        val whole = t.substringBefore('.').toLong()
        val frac = t.substringAfter('.', "").padEnd(2, '0').toLong()
        val bytes = whole * unit.bytes + frac * (unit.bytes / 100)
        return bytes.takeIf { it > 0 && it <= 1_000_000L * LimitUnit.GB.bytes }
    }

    private fun decimal(bytes: Long, unit: Long, separator: Char): String {
        val hundredths = (bytes * 100 + unit / 2) / unit
        val whole = hundredths / 100
        val frac = hundredths % 100
        return when {
            frac == 0L -> "$whole"
            frac % 10 == 0L -> "$whole$separator${frac / 10}"
            else -> "$whole$separator${frac.toString().padStart(2, '0')}"
        }
    }
}

/**
 * "Available offline" on the nodes of one tenant (a header's [me.raddatz.veyq.mobile.navigation.NodeHeaderModel.Door]
 * answers it): the account's pins and their fetches, and pinning a node — its size asked first — or taking it off.
 */
interface OfflineNodes {
    val pins: StateFlow<List<Pin>>
    val progress: StateFlow<Map<String, OfflineFetching.Progress>>

    /** Whether a node of [kind] offers the toggle. */
    fun pinnable(kind: String): Boolean

    /** The bytes pinning [ref] would keep (shown and confirmed first); null where it cannot be pinned. */
    suspend fun plan(ref: NodeAt): Long?

    /** [ref] pinned and its fetch started; false where it cannot be pinned. */
    suspend fun pin(ref: NodeAt): Boolean

    suspend fun unpin(ref: NodeAt)

    /** Each change of the pins or their fetches. */
    val changes: Flow<Unit> get() = pins.combine(progress) { _, _ -> }

    companion object {
        /** [pins] over [tenant], the kinds the build's ([me.raddatz.veyq.mobile.AppKinds.pins]). */
        fun of(pins: OfflinePins, tenant: TenantSession): OfflineNodes = object : OfflineNodes {
            override val pins get() = pins.pins
            override val progress get() = pins.progress
            override fun pinnable(kind: String) = me.raddatz.veyq.mobile.AppKinds.pins.pinnable(kind)
            override suspend fun plan(ref: NodeAt) = pins.plan(tenant, ref.scopeId, ref.nodeId)?.bytes
            override suspend fun pin(ref: NodeAt) = pins.pin(tenant, ref.scopeId, ref.nodeId)
            override suspend fun unpin(ref: NodeAt) = pins.unpin(ref.nodeId)
        }
    }
}
