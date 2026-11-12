package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece

/**
 * One link as a node's share sheet lists it, for both apps (ADR 0129; the web's `FolderShareDialog`,
 * `DropBoxShareDialog`): its title, its badges and what this member may do with it. [dropBox]: a drop box's links, which
 * are never edited or renewed (a drop box has its one key; a new link is minted instead).
 */
class LinkRow(val link: ShareLinks.Link, dropBox: Boolean, words: ShareWords) {
    /** Its label, else its role. */
    val title: Piece = if (link.label.isEmpty()) Piece.Said(Notice(words.roleWord(link.role))) else Piece.Plain(link.label)

    /** What it says beside its dates: its role where a label took the title, a password, switched off, made by another member, stopped. */
    val badges: List<Msg> = listOfNotNull(
        if (link.label.isNotEmpty()) words.roleWord(link.role) else null,
        if (link.passwordRequired) Msg.SHARE_PASSWORD_BADGE else null,
        if (!link.enabled) Msg.SHARE_SWITCHED_OFF else null,
        if (!link.mine) Msg.SHARE_BY_MEMBER else null,
        if (link.stopped || link.reissueRequired) Msg.SHARE_STOPPED else null,
    )

    /** Copied or shared: an open link whose address this device can rebuild. */
    val canCopy: Boolean = link.enabled && link.url != null

    /** Edited in place: an own open link whose address this device can rebuild. */
    val canEdit: Boolean = !dropBox && link.mine && link.enabled && link.url != null

    /** Renewed (a new address, the old one closed): an own link, or one switched off. */
    val canRenew: Boolean = !dropBox && (link.mine || !link.enabled)

    /** Switched off at once: an own open link. */
    val canSwitchOff: Boolean = link.enabled && link.mine

    /** Switched on again at its old address (any member: it becomes theirs): one switched off that the server did not close for good. */
    val canReactivate: Boolean = !link.enabled && !link.reissueRequired

    /** Deleted for good: one switched off that the server lets go. */
    val canDelete: Boolean = !link.enabled && link.deletable

    /** What the new-link form's answer makes: on a folder, "add files only" makes a drop box with its first link. */
    class Mint(val makesDropBox: Boolean, val role: String, val label: String)

    companion object {
        /** The form's [role] and [label] as minted on a node ([onDropBox]: a drop box, whose links are upload-only and unnamed). */
        fun mint(role: String, label: String, onDropBox: Boolean): Mint = when {
            onDropBox -> Mint(false, ShareLinks.WRITE_ONLY, "")
            role == ShareLinks.WRITE_ONLY -> Mint(true, role, label)
            else -> Mint(false, role, label)
        }
    }
}
