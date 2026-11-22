package me.raddatz.veyq.mobile.memories.tracks

import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.math.floor

/**
 * The numbers beside a track as the list says them (the web's `trackFacts.ts`; ADR 0130 decision 4): its distance, its
 * duration, its climb and its descent, each left out where the file does not give it. Which unit and how many digits
 * are the core's; the platform writes the [Fact.Distance]'s number in its locale (`12.3 km`, `12,3 km`).
 */
object TrackFacts {
    sealed interface Fact {
        /** A length: [value] metres (whole) or kilometres (one decimal) — [kilometres] says which. */
        data class Distance(val value: Double, val kilometres: Boolean) : Fact

        /** A phrase in words: a duration, a climb, a descent. */
        data class Words(val notice: Notice) : Fact
    }

    /** The facts of [stats] in their order: distance, duration, climb, descent. */
    fun of(stats: Gpx.Stats): List<Fact> {
        val facts = ArrayList<Fact>()
        val (value, km) = Gpx.roundedDistance(stats.distanceMeters)
        facts += Fact.Distance(value, km)
        stats.durationMs?.let { ms ->
            val minutes = Gpx.minutes(ms)
            val h = minutes / 60
            val m = minutes % 60
            facts += Fact.Words(if (h > 0) Notice(Msg.TRACK_DURATION_HOURS, h, m) else Notice(Msg.TRACK_DURATION_MINUTES, m))
        }
        stats.ascentMeters?.let { facts += Fact.Words(Notice(Msg.TRACK_ASCENT, whole(it))) }
        stats.descentMeters?.let { facts += Fact.Words(Notice(Msg.TRACK_DESCENT, whole(it))) }
        return facts
    }

    /** A height in whole metres (`maximumFractionDigits: 0`, halves up). */
    private fun whole(m: Double): Int = floor(m + 0.5).toInt()
}
