package me.raddatz.veyq.mobile.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A node's action (ADR 0154 decision 2, ADR 0110): every entry its header's [NodeHeader.Plan] and its row's menu
 * ([LevelRow.Plan]) can hold — core's own and every application's — so a platform draws each one exhaustively. The serial
 * names are the web's ids (`nodeHeader.ts`, `coreNodeActions.ts`, each application's `register.ts` `header.order`), the
 * words [NodeHeader.label] and the kind's page model give them. [slot] is where it may go: a button where it fits
 * ([NodeHeader.Slot.ANY]), the menu ([NodeHeader.Slot.MENU]) or the menu's last group ([NodeHeader.Slot.LAST]).
 *
 * Who offers which: core's header the first ten ([NodeHeader.plan], under [NodeHeader.Rights]); a Memory's or chapter's
 * page [UPLOAD], [MUSIC] and [BANNER] (`GalleryModel.headerActions`); a file's page [REPLACE] and [SEND] (`FileModel`'s
 * header); a kept link's page [OPEN_OWN_PAGE] and [REPORT_SHARED] (`BookmarkPage.headerActions`). A platform adds none of
 * its own.
 */
@Plan
@Serializable
enum class HeaderAction(val slot: NodeHeader.Slot) {
    @SerialName("favorite") FAVORITE(NodeHeader.Slot.ANY),
    @SerialName("share") SHARE(NodeHeader.Slot.ANY),
    /** An app has no rename in place: a menu entry, never a button. */
    @SerialName("rename") RENAME(NodeHeader.Slot.MENU),
    @SerialName("move") MOVE(NodeHeader.Slot.LAST),
    @SerialName("copy") COPY(NodeHeader.Slot.LAST),
    /** An app's cover is no visual one taps: a menu entry. */
    @SerialName("cover") COVER(NodeHeader.Slot.MENU),
    @SerialName("download") DOWNLOAD(NodeHeader.Slot.ANY),
    /** "Available offline" (ADR 0140 decision 10): a toggle in the menu, its state the header's (`NodeHeaderModel.State.offline`). */
    @SerialName("offline") OFFLINE(NodeHeader.Slot.MENU),
    /** Report (ADR 0093); on a kept link its name, to this instance (`ShellModel.reportSheet`, the web's `report-name`). */
    @SerialName("report") REPORT(NodeHeader.Slot.LAST),
    @SerialName("delete") DELETE(NodeHeader.Slot.LAST),
    /** A Memory's or chapter's photos and videos added (`GalleryModel`). */
    @SerialName("upload") UPLOAD(NodeHeader.Slot.ANY),
    /** A Memory's music played or stopped (ADR 0142 decision 6). */
    @SerialName("music") MUSIC(NodeHeader.Slot.ANY),
    /** A Memory's banner added or changed (ADR 0144 decision 5): a menu entry, as the web's `menuOnly`. */
    @SerialName("banner") BANNER(NodeHeader.Slot.MENU),
    /** A file's content replaced, or the first upload into an empty one (`FileModel`). */
    @SerialName("replace") REPLACE(NodeHeader.Slot.ANY),
    /** A file's copy handed to another app through the system's share sheet (a phone's, `FileRights.send`). */
    @SerialName("send") SEND(NodeHeader.Slot.MENU),
    /** A kept link opened on its own page (ADR 0150 decision 6, the web's `BookmarkDetail`). */
    @SerialName("open-own-page") OPEN_OWN_PAGE(NodeHeader.Slot.ANY),
    /** A kept link's content reported to the share's own operator (ADR 0150 decision 6, the web's bookmark `report`). */
    @SerialName("report-shared") REPORT_SHARED(NodeHeader.Slot.LAST),
}
