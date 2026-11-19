package me.raddatz.veyq.mobile.bridge

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.raddatz.veyq.mobile.memories.GalleryFilter

/**
 * The desktop's calendar for a gallery filter's day chips (inbox #989, ADR 0149 decision 5): a capture time's day in the
 * device's own zone (kotlinx-datetime's system zone, as the wall clock reads it: `/etc/localtime` on Linux, the system's on
 * Windows), the chip labelled by that ISO day — the host says a day its own way (Linux's `said::day`), as it says every
 * capture time.
 */
internal val LocalCalendar = GalleryFilter.Calendar({ localDay(it) }) { at, _ -> localDay(at) }

internal fun localDay(epochMs: Long): String =
    kotlin.time.Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
