package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.sharing.LinkDays
import me.raddatz.veyq.mobile.words.Msg
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The words of a node's share sheet by its kind (the web's `ShareDialogCopy`): a drop box's, a file's, a Pot's, else
 * core's folder words.
 */
class ShareWords(
    val title: Msg, val intro: Msg, val contributor: Msg, val viewerHint: Msg, val contributorHint: Msg, val deleteConfirm: Msg,
    /** The upload-only role's name and hint ("Add files only", or a Memory's "Add photos only"). */
    val uploader: Msg = Msg.SHARE_ROLE_UPLOADER, val uploaderHint: Msg = Msg.DROPBOX_UPLOAD_HINT,
) {
    /** The hint under the role picked. */
    fun hint(role: String): Msg = when (role) {
        ShareLinks.READ -> viewerHint
        ShareLinks.WRITE_ONLY -> uploaderHint
        else -> contributorHint
    }

    /** A role in this sheet's words. */
    fun roleWord(role: String): Msg = when (role) {
        ShareLinks.READ -> Msg.SHARE_ROLE_VIEWER
        ShareLinks.WRITE_ONLY -> uploader
        else -> contributor
    }

    companion object {
        /** The words of a node of [kind]'s share sheet: its application's (`ApplicationKinds.shareWords`), else core's folder words. */
        fun of(kind: String): ShareWords = me.raddatz.veyq.mobile.AppKinds.shareWords(kind) ?: ShareWords(
            Msg.SHARE_FOLDER_TITLE, Msg.SHARE_FOLDER_INTRO, Msg.SHARE_FOLDER_ROLE_CONTRIBUTOR,
            Msg.SHARE_FOLDER_ROLE_VIEWER_HINT, Msg.SHARE_FOLDER_ROLE_CONTRIBUTOR_HINT, Msg.SHARE_FOLDER_DELETE_CONFIRM,
        )
    }
}

/**
 * The form of a new link or of one being edited (the web's `FolderShareDialog`): its role, an optional label, a password
 * and an end. A rule of the tenant turns its switch on and keeps it on; the end is a day from today to the rule's last
 * ([lastDay]: [LinkDays.lastEndDay], counted from when the link was made — an edited one's creation, not today, as the
 * server counts; inbox #1057), sent as the end of that local day. Days are the device's local calendar days as epoch days
 * ([LocalDays], every platform's zone read one way).
 * Each change is a new form ([withRole] …), so a UI holds it as its state.
 */
class LinkForm private constructor(
    val rules: ShareLinks.Rules,
    /** The link an edit changes (its password left blank stays); null mints a new one. */
    val editing: ShareLinks.Link?,
    /** A drop box's own link: its role is fixed and it has no name of its own. */
    val writeOnly: Boolean,
    /** A folder's third role, "Add files only": it makes a drop box with the name typed. */
    val uploadOffered: Boolean,
    /** That role alone (a space's top level): no role to pick. */
    val uploadOnly: Boolean,
    /**
     * The third role is a link on this node, not a drop box made (a Memory's or a chapter's "Add photos only": its guests
     * seal to the node's own drop key); its name is the member's label, optional as any other.
     */
    val uploadLink: Boolean,
    val today: Long,
    /** The last day an end may be ([LinkDays.lastEndDay] from the link's creation), or null where any day may. It lies
     *  before [today] for an edited link past its lifetime: then no day is offered that the server takes (only a renew). */
    val lastDay: Long?,
    val role: String,
    val label: String,
    val withPassword: Boolean,
    val password: String,
    val withEnd: Boolean,
    val endDay: Long,
) {
    private fun with(
        role: String = this.role, label: String = this.label, withPassword: Boolean = this.withPassword,
        password: String = this.password, withEnd: Boolean = this.withEnd, endDay: Long = this.endDay,
    ) = LinkForm(rules, editing, writeOnly, uploadOffered, uploadOnly, uploadLink, today, lastDay, role, label, withPassword, password, withEnd, endDay)

    fun withRole(role: String) = if (role in roles) with(role = role) else this
    fun withLabel(label: String) = with(label = label)
    /** The password's switch: kept on where the tenant requires one. */
    fun withPasswordOn(on: Boolean) = with(withPassword = on || rules.passwordRequired)
    fun withPassword(password: String) = with(password = password)
    /** The end's switch: kept on where the tenant limits a link's days. */
    fun withEndOn(on: Boolean) = with(withEnd = on || rules.maxDays != null)
    /** The end, held to the days a pick may take (the last day wins over today where a link outlived its limit). */
    fun withEndDay(day: Long) = with(endDay = capped(maxOf(day, today), lastDay))

    /** The roles to pick from, in order; empty where the role is fixed. */
    val roles: List<String>
        get() = when {
            writeOnly || uploadOnly -> emptyList()
            else -> listOfNotNull(
                ShareLinks.READ, ShareLinks.READ_WRITE.takeIf { rules.contribute }, ShareLinks.WRITE_ONLY.takeIf { uploadOffered },
            )
        }

    /** Whether a name field is shown: a drop box's own links have none; the one "Add files only" makes needs one. */
    val namesIt: Boolean get() = !writeOnly

    /** The name field's label: a drop box's name its guests see, else a label only the member sees. */
    val nameLabel: Msg get() = if (role == ShareLinks.WRITE_ONLY && !uploadLink) Msg.SHARE_UPLOAD_NAME else Msg.SHARE_LABEL

    /** Whether the switches may be turned off (a rule holds them on). */
    val passwordOptional: Boolean get() = !rules.passwordRequired
    val endOptional: Boolean get() = rules.maxDays == null

    /** A link held an old password: a blank field keeps it. */
    val hadPassword: Boolean get() = editing?.passwordRequired == true

    /** A password typed and too short (shown as such while typing). */
    val passwordTooShort: Boolean get() = password.isNotEmpty() && password.trim().length < PASSWORD_MIN

    /** Whether the form may be sent: a password long enough (or an old one kept), and a drop box named. */
    val canSave: Boolean
        get() {
            val passwordOk = !withPassword || password.trim().length >= PASSWORD_MIN || (hadPassword && password.isEmpty())
            val named = role != ShareLinks.WRITE_ONLY || writeOnly || uploadLink || label.isNotBlank()
            return passwordOk && named
        }

    /** The password sent: null leaves it as it is, blank removes or sets none, else the new one. */
    val submittedPassword: String?
        get() = when {
            withPassword && password.isEmpty() && hadPassword -> null
            withPassword -> password
            hadPassword -> ""
            editing != null -> null
            else -> ""
        }

    /** The end day sent (the platform sends the end of that local day), or null for none. */
    val submittedEndDay: Long? get() = if (withEnd) endDay else null

    companion object {
        /** A link's password: at least this many characters (`FolderShareDialog`'s `PASSWORD_MIN`). */
        const val PASSWORD_MIN = 10

        /** Days a new link's end lies ahead when nothing else sets it. */
        const val DEFAULT_DAYS = 30

        private fun capped(day: Long, last: Long?): Long = if (last != null && day > last) last else day

        /**
         * The form as it opens at [now] (epoch ms): an edited link's settings ([editingEndDay] its end as a local day), else
         * a new link's — the role a fixed one or view only, each switch on where a rule holds it. The last day counts from
         * the edited link's `createdAt` (a new one is made now), so no caller hands an edit a day counted from today.
         */
        @OptIn(ExperimentalTime::class)
        fun start(
            rules: ShareLinks.Rules, editing: ShareLinks.Link?, writeOnly: Boolean, uploadOffered: Boolean, uploadOnly: Boolean,
            now: Long, editingEndDay: Long?, uploadLink: Boolean = false,
        ): LinkForm {
            val today = LocalDays.dayOf(now)
            val madeAt = editing?.let { runCatching { Instant.parse(it.createdAt).toEpochMilliseconds() }.getOrNull() } ?: now
            val last = LinkDays.lastEndDay(rules.maxDays, madeAt.toDouble())?.toLong()
            return LinkForm(
                rules, editing, writeOnly, uploadOffered, uploadOnly, uploadLink, today, last,
                role = if (writeOnly || uploadOnly) ShareLinks.WRITE_ONLY else editing?.role ?: ShareLinks.READ,
                label = editing?.label.orEmpty(),
                withPassword = rules.passwordRequired || editing?.passwordRequired == true,
                password = "",
                withEnd = rules.maxDays != null || editingEndDay != null,
                endDay = capped(editingEndDay ?: last ?: (today + DEFAULT_DAYS), last),
            )
        }
    }
}
