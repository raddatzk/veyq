@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.sharing.web

import me.raddatz.veyq.mobile.session.ClientWindow
import me.raddatz.veyq.mobile.sharing.LinkDays
import me.raddatz.veyq.mobile.sharing.ReceivedLink
import me.raddatz.veyq.mobile.sharing.ShareInstance
import kotlin.js.Date
import kotlin.js.collections.JsReadonlyArray

// The share links' façade for the web (ADR 0155 decisions 4, 5 and 7.2; `@veyq/core-js/sharing`): the platform's
// `sharing` rules — a received link's check and its report fragment (`ReceivedLink`), a link's last end day under a
// tenant's limit (`LinkDays`), an instance's embeddability (`ShareInstance`) and the client window (`ClientWindow`) —
// behind plain values (`Shapes.kt`), so the web checks, offers and embeds as the apps do and keeps only what a browser
// does: its dialogs, its fetch of `/api/setup`, its own list of embed adapters. Nothing here reaches
// kotlinx.serialization, coroutines, Ktor or a `NotOnTheWeb` refusal (the package's slice check reads the shipped bundle's
// sources), and no `Long` crosses (an instant is a `Double` of epoch milliseconds, a day an `Int` of epoch days).

/** A pasted text checked and normalized ([ReceivedLink.parse]); fails closed. */
@JsExport
fun parseShareLink(input: String): ParsedShape {
    val o: dynamic = js("({})")
    when (val p = ReceivedLink.parse(input)) {
        is ReceivedLink.Parsed.Ok -> {
            o.link = p.link.link
            o.origin = p.link.origin
            o.shareId = p.link.shareId
            o.report = p.report
        }
        is ReceivedLink.Parsed.Refused -> {
            o.report = false
            o.problem = when (p.problem) {
                ReceivedLink.Problem.EMPTY -> "empty"
                ReceivedLink.Problem.TOO_LONG -> "tooLong"
                ReceivedLink.Problem.NOT_HTTPS -> "notHttps"
                ReceivedLink.Problem.CREDENTIALS -> "credentials"
                ReceivedLink.Problem.NOT_A_SHARE -> "notAShare"
                ReceivedLink.Problem.NO_KEY, ReceivedLink.Problem.MODERATION -> "noKey"
                ReceivedLink.Problem.NOT_A_URL -> "notAUrl"
            }
        }
    }
    return o.unsafeCast<ParsedShape>()
}

/** A kept link read back ([ReceivedLink.kept]): only its normalized form itself reads; null otherwise. */
@JsExport
fun keptLink(link: String): LinkShape? {
    val k = ReceivedLink.kept(link) ?: return null
    val o: dynamic = js("({})")
    o.link = k.link
    o.origin = k.origin
    o.shareId = k.shareId
    return o.unsafeCast<LinkShape>()
}

/** The host a link's [origin] names, as a reader is shown it (a port kept). */
@JsExport
fun hostOf(origin: String): String = ReceivedLink.hostOf(origin)

/** The kept [link] with the share page asked to open its report dialog (ADR 0120 decision 6). */
@JsExport
fun reportLinkOf(link: String): String = "$link&report"

/** Whether a share page's [fragment] asks for its report dialog. */
@JsExport
fun asksForReport(fragment: String): Boolean = ReceivedLink.asksForReport(fragment)

/** The [fragment] without its `report` parameter. */
@JsExport
fun withoutReport(fragment: String): String = ReceivedLink.withoutReport(fragment)

/** The last local day (epoch days, this browser's zone) a link made at [madeAt] (epoch ms) may end on; null without a limit. */
@JsExport
fun lastEndDay(maxDays: Int?, madeAt: Double): Int? = LinkDays.lastEndDay(maxDays, madeAt, ::localDay)

/** The local day [ms] falls on in this browser's zone, as epoch days: the engine's own `Date`, no `Long` on the way. */
private fun localDay(ms: Double): Int {
    val d = Date(ms)
    return (Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000.0).toInt()
}

/**
 * Whether a share's instance, as its [setup] was read (null: no answer), may be shown in place on this page
 * ([ShareInstance.embedding]): null where it embeds, else why not. [on] is this instance's `core.share-embedding`, [majors]
 * the majors this client's embed speaks. A major that is no whole number is spoken by none.
 */
@JsExport
fun embedding(on: Boolean, setup: SetupShape?, majors: JsReadonlyArray<Int>): String? {
    val s: dynamic = setup
    val v: dynamic = if (setup == null) null else s.version
    val m: dynamic = if (v == null) null else v.major
    val major: Int? = if (jsTypeOf(m) != "number") null else (m as Double).let { if (it.toInt().toDouble() == it) it.toInt() else Int.MIN_VALUE }
    val caps: dynamic = if (setup == null) null else s.capabilities
    val ids: List<String>? = if (!isArray(caps)) null else {
        val out = ArrayList<String>()
        for (c in caps.unsafeCast<Array<dynamic>>()) {
            val id: dynamic = if (c == null) null else c.id
            if (jsTypeOf(id) == "string") out.add(id.unsafeCast<String>())
        }
        out
    }
    val spoken = majors.unsafeCast<Array<Int>>()
    return ShareInstance.embedding(on, major, ids) { it in spoken }
}

/** The older majors ADR 0085 decision 5's planned window keeps for a client of [major]. */
@JsExport
fun olderMajorsInWindow(major: Int): JsReadonlyArray<Int> = ClientWindow.olderMajorsInWindow(major).toTypedArray().unsafeCast<JsReadonlyArray<Int>>()

private fun isArray(a: dynamic): Boolean = js("Array").isArray(a).unsafeCast<Boolean>()
