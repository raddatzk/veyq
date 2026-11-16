package me.raddatz.veyq.mobile.memories.place

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.session.LocalDays

/**
 * A Memory's curated date (uc.memory.date; ADR 0143 D5) — the port of the web's `memoryDate.ts`: the `date` sub-object of
 * the node's sealed meta (`node-meta` under its key, beside the place and the cover), its effective day range
 * materialized for every mode so a list shows it without the Memory's media. Days are `yyyy-MM-dd`, the device's local
 * ones. `single` has `from == to`; `range` both; `ongoing` a `from` and no `to`; a materialized `auto` both, or neither
 * where the Memory has no trustworthy dated media.
 */
data class MemoryDate(val mode: Mode, val from: String? = null, val to: String? = null) {
    enum class Mode(val token: String) {
        AUTO("auto"), SINGLE("single"), RANGE("range"), ONGOING("ongoing");

        companion object {
            fun of(token: String?): Mode? = entries.firstOrNull { it.token == token }
        }
    }

    /** The sub-object as the blob holds it: `{mode, from?, to?}` in the web's key order, absent ends left out. */
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>("mode" to JsonPrimitive(mode.token)).also { m ->
            from?.let { m["from"] = JsonPrimitive(it) }
            to?.let { m["to"] = JsonPrimitive(it) }
        },
    )

    companion object {
        /** The neutral "no date yet" state: `auto` with no range. */
        val NONE = MemoryDate(Mode.AUTO)

        private val ISO_DAY = Regex("""\d{4}-\d{2}-\d{2}""")

        private fun day(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { ISO_DAY.matches(it) }

        /** A loosely typed `date` read as a [MemoryDate], or null where its mode is not one of the four (`normalizeDate`). */
        fun normalize(raw: JsonElement?): MemoryDate? {
            val o = raw as? JsonObject ?: return null
            val mode = Mode.of((o["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content) ?: return null
            return MemoryDate(mode, day(o["from"]), day(o["to"]))
        }

        /** The date a node's opened meta carries, or null. */
        fun of(meta: JsonObject): MemoryDate? = normalize(meta[FIELD])

        /** The blob's field. */
        const val FIELD = "date"

        /** Whether `from..to` is a range the editor may save: both days, `to` not before `from` (`isValidMemoryDateRange`). */
        fun isValidRange(from: String?, to: String?): Boolean = !from.isNullOrEmpty() && !to.isNullOrEmpty() && to >= from

        /**
         * A Memory's `auto` range from its own media, day-truncated in the device's zone: only **trustworthy** capture
         * times count — a member's correction, or one from EXIF or a video's container —, so a file-date fallback cannot
         * stretch it (`computeAutoRange`). None: [NONE].
         */
        fun autoRange(items: List<MemoryMedia.Item>, isoOf: (Long) -> String = LocalDays::isoOf): MemoryDate {
            val times = items.mapNotNull { it.meta }.mapNotNull { m ->
                val trustworthy = m.capturedAtOverride != null || m.capturedAtSource == "exif" || m.capturedAtSource == "container"
                m.effectiveCapturedAt?.takeIf { trustworthy }
            }
            if (times.isEmpty()) return NONE
            return MemoryDate(Mode.AUTO, isoOf(times.min()), isoOf(times.max()))
        }

        /** What the header and the row show: a manual date as sealed, else the sealed `auto` range, else [auto] (`effectiveDate`). */
        fun effective(stored: MemoryDate?, auto: MemoryDate): MemoryDate =
            if (stored != null && (stored.mode != Mode.AUTO || stored.from != null)) stored else auto

        /**
         * The `auto` self-heal's edit (`reconcileMemoryDate`): the blob's date set to [next] where it is `auto` (or absent)
         * and differs — never over a manual date, decided on the blob being written (on a `409` again on the fresh one, so
         * a peer's manual date is never overwritten); null for no write.
         */
        fun reconcile(next: MemoryDate): (JsonObject) -> JsonObject? = { meta ->
            val stored = of(meta)
            when {
                stored != null && stored.mode != Mode.AUTO -> null // a manual date is the member's
                (stored ?: NONE) == next -> null
                stored == null && next.from == null -> null // absent meta already is the empty `auto`
                else -> JsonObject(meta + (FIELD to next.toJson()))
            }
        }
    }
}
