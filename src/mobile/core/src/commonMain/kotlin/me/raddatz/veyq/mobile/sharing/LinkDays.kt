package me.raddatz.veyq.mobile.sharing

import me.raddatz.veyq.mobile.session.LocalDays

/**
 * The last local day a link may end on under a tenant's day limit (ADR 0088 decision 8, `uc.admin.capability-requirements`),
 * for every client: the apps' link form ([me.raddatz.veyq.mobile.session.LinkForm]) and the web's date pickers
 * (`@veyq/core-js/sharing`). The server refuses an end past `createdAt + maxDays × 24 h`, counted from when the link was
 * MADE — so an edited link counts from its creation, never from today (inbox #1057: the apps once counted from today and
 * offered an edit a day the save refused). A picked day is sent as its end (`LocalDays.endOf`), so the last day is the
 * one before the local day the limit falls on. Held by `contracts/platform/share-links/cases.v1.json` (`lastDay`).
 */
object LinkDays {
    private const val DAY_MS = 86_400_000L

    /** The last local day (epoch days, the device's zone) a link made at [madeAt] (epoch ms) may end on; null without a limit. */
    fun lastEndDay(maxDays: Int?, madeAt: Long): Long? = maxDays?.let { LocalDays.dayOf(madeAt + it * DAY_MS) - 1 }
}
