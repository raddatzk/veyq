@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.sharing.web

import kotlin.js.collections.JsReadonlyArray

// The plain values the share links' façade takes and returns (ADR 0155 decision 5): each an external interface, so its
// `.d.ts` names every field with its type and the web passes its own objects (its generated `SetupStatus` is a
// `SetupShape`) and reads plain ones back. A `LinkShape` holds the link as pasted and kept, its key in the fragment — the
// web's own `ShareLink` holds the same; it never reaches anything but the bookmark it is sealed into.

/** A link that may be kept: its normalized form (`<origin>/s/<shareId>#k=<key>`), its origin and its share id. */
@JsExport
external interface LinkShape {
    val link: String
    val origin: String
    val shareId: String
}

/** A pasted text checked: the link ([link] … [report]) where it is one, else why not ([problem]: `empty`, `tooLong`,
 *  `notAUrl`, `notHttps`, `credentials`, `notAShare`, `noKey`). [report]: its fragment asks for the report dialog. */
@JsExport
external interface ParsedShape {
    val link: String?
    val origin: String?
    val shareId: String?
    val report: Boolean
    val problem: String?
}

/** A share's instance's `/api/setup` as read: only these fields are read, each checked for its type (another instance's
 *  answer is anything it sent). */
@JsExport
external interface SetupShape {
    val version: VersionShape?
    val capabilities: JsReadonlyArray<CapabilityShape>?
}

@JsExport
external interface VersionShape {
    val major: Double
}

@JsExport
external interface CapabilityShape {
    val id: String
}
