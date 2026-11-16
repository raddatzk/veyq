package me.raddatz.veyq.mobile.memories.tracks

import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The numbers beside a track and a track's label (the web's `track/trackFacts.test.ts` and `track/trackLabel.test.ts`; ADR
 * 0130 decisions 4 and 9): the distance in its unit and digits, the duration, climb and descent in words, each left out
 * where the file does not give it; the activities read, the label's fields, and each activity's dash.
 */
class TrackFactsTest {
    private fun words(msg: Msg, vararg args: Any) = TrackFacts.Fact.Words(Notice(msg, args.toList()))

    @Test
    fun theDistanceTheDurationTheClimbAndTheDescentAreSaid() {
        assertEquals(
            listOf(
                TrackFacts.Fact.Distance(12.3, kilometres = true),
                words(Msg.TRACK_DURATION_HOURS, 3, 12),
                words(Msg.TRACK_ASCENT, 420),
                words(Msg.TRACK_DESCENT, 410),
            ),
            TrackFacts.of(Gpx.Stats(12_345.0, durationMs = (3 * 60 + 12) * 60_000L, ascentMeters = 420.4, descentMeters = 410.0)),
        )
    }

    @Test
    fun whatTheFileDoesNotGiveIsLeftOutAndAShortTimeSaidInMinutes() {
        assertEquals(listOf(TrackFacts.Fact.Distance(800.0, kilometres = false)), TrackFacts.of(Gpx.Stats(800.0)))
        assertEquals(
            listOf(TrackFacts.Fact.Distance(800.0, false), words(Msg.TRACK_DURATION_MINUTES, 25)),
            TrackFacts.of(Gpx.Stats(800.0, durationMs = 25 * 60_000L)),
        )
        assertEquals(
            listOf(TrackFacts.Fact.Distance(1.0, true), words(Msg.TRACK_DURATION_HOURS, 1, 30), words(Msg.TRACK_ASCENT, 5), words(Msg.TRACK_DESCENT, 0)),
            TrackFacts.of(Gpx.Stats(1000.0, durationMs = 90 * 60_000L, ascentMeters = 5.0, descentMeters = 0.0)),
        )
    }

    @Test
    fun aKnownActivityIsReadAndAMissingOrANewerClientsValueIsNone() {
        assertEquals("cycling", TrackLabel.activityOf("cycling"))
        assertNull(TrackLabel.activityOf(null))
        assertNull(TrackLabel.activityOf("paragliding"))
        assertEquals(10, TrackLabel.ACTIVITIES.size)
    }

    @Test
    fun aLabelBecomesTheTierFieldsAnUploadSealsLeavingOutWhatWasNotGiven() {
        assertEquals(emptyList(), TrackLabel.facts(TrackLabel.Label(null, null)))
        assertEquals(emptyList(), TrackLabel.facts(TrackLabel.Label("   ", "kayaking")), "a blank name and an unknown activity are none")
        assertEquals(listOf("trackName", "trackActivity"), TrackLabel.facts(TrackLabel.Label(" Alps ", "hiking")).map { it.first })
        assertEquals("Alps", TrackLabel.facts(TrackLabel.Label(" Alps ", null)).single().second.content)
    }

    @Test
    fun onFootOtherAndNoneAreSolidAndEveryOtherActivityHasTheOldApplicationsDash() {
        for (a in listOf(null, "hiking", "walking", "other")) assertNull(TrackLabel.dash(a), "$a")
        assertEquals(listOf(2, 8), TrackLabel.dash("running"))
        for (a in listOf("cycling", "road-cycling", "gravel-cycling")) assertEquals(listOf(10, 7), TrackLabel.dash(a))
        assertEquals(listOf(10, 5, 2, 5), TrackLabel.dash("mountain-biking"))
        assertEquals(listOf(14, 6, 3, 6), TrackLabel.dash("skiing"))
        assertEquals(listOf(3, 9), TrackLabel.dash("water"))
    }
}
