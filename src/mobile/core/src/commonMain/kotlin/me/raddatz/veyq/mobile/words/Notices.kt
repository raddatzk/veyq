package me.raddatz.veyq.mobile.words

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeRename
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks

/**
 * What a screen says, as the catalog's key and its arguments (ADR 0136 decisions 1 and 3): each platform renders it in
 * the person's language. An argument is a `String`, an `Int`, a [Bytes], a [Moment] or an [Amount] (each written by
 * [Shown] in the app's language, [Shown.args]), another [Notice] (a word inside the sentence) or a [Joined] list of those.
 *
 * Shown as a notice of its own (a snackbar, a toast, an InfoBar), it lives as its [kind] says (ADR 0154 decision 4):
 * [NoticeKind.of] its word's default, which a raising site changes where the same word lives otherwise (a move with its
 * undo offered is [NoticeKind.UNDO], one without is [NoticeKind.SAID]). As a line of a screen its kind is not read.
 */
@Serializable(with = NoticeSerializer::class)
class Notice(val msg: Msg, val args: List<Any> = emptyList(), val kind: NoticeKind = NoticeKind.of(msg)) {
    constructor(msg: Msg, vararg args: Any) : this(msg, args.toList())

    /** The same words, living as [kind] says. */
    fun living(kind: NoticeKind): Notice = Notice(msg, args, kind)

    override fun equals(other: Any?) = other is Notice && other.msg == msg && other.args == args && other.kind == kind
    override fun hashCode() = (msg.hashCode() * 31 + args.hashCode()) * 31 + kind.hashCode()
    override fun toString() = "Notice(${msg.key}, $args${if (kind == NoticeKind.of(msg)) "" else ", $kind"})"

    companion object {
        /** How long a [NoticeKind.SAID] notice shows before it closes itself (the web's `SUCCESS_LIFE_MS`). */
        const val SAID_MS = 4_000L

        /** How long "copied" shows beside what was copied (the web's copy notes). */
        const val COPIED_MS = 2_000L

        /** How long the player says that nothing plays (the web's `AudioBar` `NOTICE_MS`). */
        const val NOTHING_PLAYS_MS = 5_000L
    }
}

/**
 * How long a notice shown on its own lives (ADR 0154 decision 4, the web's `useToast`): a platform maps it onto its
 * control and holds no duration of its own.
 */
enum class NoticeKind {
    /** Said briefly — a success: it closes itself after [Notice.SAID_MS]. */
    SAID,

    /** A refusal or a failure: it stays until the person closes it, so it carries a close action. */
    HELD,

    /** An offer to undo: it stays while the state offers the undo (the core's window, `ShellModel.TRASH_UNDO_MS`). */
    UNDO;

    /** Milliseconds it shows before it closes itself; null while it stays (until closed, or until the state drops it). */
    val lifeMs: Long? get() = if (this == SAID) Notice.SAID_MS else null

    companion object {
        /** The words that say something went through (the web's `toast.success`), each reviewed. */
        private val SAID_WORDS = setOf(
            Msg.COPY_DONE, Msg.MOVE_MOVED, Msg.DOWNLOAD_DONE, Msg.DOWNLOAD_CANCELLED, Msg.IMPORT_IMPORTED,
            Msg.IMPORT_IMPORTED_LEFT_OUT, Msg.OFFLINE_SENT,
            // a platform's own success, said as a notice since every app maps the kind (ADR 0154 decision 4)
            Msg.LINK_KEPT, Msg.PROFILE_SAVED, Msg.RECEIPTS_SAVED_GALLERY, Msg.RECEIPTS_SAVED,
        )

        /** The words that offer an undo (the web's `showUndo`). */
        private val UNDO_WORDS = setOf(Msg.TRASH_MOVED, Msg.MEMORY_MOVED_TO_TRASH)

        /** The kind [msg] lives as unless its site says otherwise: a refusal or a failure, which every other word is. */
        fun of(msg: Msg): NoticeKind = when (msg) {
            in SAID_WORDS -> SAID
            in UNDO_WORDS -> UNDO
            else -> HELD
        }
    }
}

/** Several values in a sentence, one after another with [separator] between them; each a `String` or a [Notice]. */
class Joined(val parts: List<Any>, val separator: String) {
    override fun equals(other: Any?) = other is Joined && other.parts == parts && other.separator == separator
    override fun hashCode() = parts.hashCode() * 31 + separator.hashCode()
    override fun toString() = "Joined($parts, '$separator')"
}

/**
 * One piece of a row's line: words of the catalog, a name as written, a day (ISO, `2026-11-10`), a size in bytes or an
 * amount in a currency's minor units — the last three written by [Shown.piece] in the app's language, the pieces joined
 * by [Shown.joined]. On the wire ([PieceSerializer]) a piece is words or a string: the bridge writes its value first.
 */
@Serializable(with = PieceSerializer::class)
sealed class Piece {
    class Said(val notice: Notice) : Piece() {
        override fun equals(other: Any?) = other is Said && other.notice == notice
        override fun hashCode() = notice.hashCode()
        override fun toString() = "Said($notice)"
    }

    class Plain(val text: String) : Piece() {
        override fun equals(other: Any?) = other is Plain && other.text == text
        override fun hashCode() = text.hashCode()
        override fun toString() = "Plain($text)"
    }

    class Day(val iso: String) : Piece() {
        override fun equals(other: Any?) = other is Day && other.iso == iso
        override fun hashCode() = iso.hashCode()
        override fun toString() = "Day($iso)"
    }

    class Size(val bytes: Long) : Piece() {
        override fun equals(other: Any?) = other is Size && other.bytes == bytes
        override fun hashCode() = bytes.hashCode()
        override fun toString() = "Size($bytes)"
    }

    class Money(val amount: Long, val currency: String?, val minorUnits: Int) : Piece() {
        override fun equals(other: Any?) = other is Money && other.amount == amount && other.currency == currency && other.minorUnits == minorUnits
        override fun hashCode() = amount.hashCode()
        override fun toString() = "Money($amount $currency/$minorUnits)"
    }
}

/** A moment in a sentence (epoch ms): each platform writes it as its own date and time read ("Nov 10, 2026, 14:05"). */
class Moment(val epochMs: Long) {
    override fun equals(other: Any?) = other is Moment && other.epochMs == epochMs
    override fun hashCode() = epochMs.hashCode()
    override fun toString() = "Moment($epochMs)"
}

/** An amount in a sentence, in a currency's minor units: each platform writes it as its own money reads ("12,34 €"). */
class Amount(val amount: Long, val currency: String?, val minorUnits: Int) {
    override fun equals(other: Any?) = other is Amount && other.amount == amount && other.currency == currency && other.minorUnits == minorUnits
    override fun hashCode() = amount.hashCode()
    override fun toString() = "Amount($amount $currency/$minorUnits)"
}

/** A count of bytes in a sentence: each platform writes it as its own file sizes read ("1.2 MB"). */
class Bytes(val count: Long) {
    override fun equals(other: Any?) = other is Bytes && other.count == count
    override fun hashCode() = count.hashCode()
    override fun toString() = "Bytes($count)"
}

/**
 * A [Msg] on the wire (the native bridge, ADR 0123's Consequences, the bridge) as its catalog key — `words.json`'s, which
 * each platform's words are generated under — never as the entry's Kotlin name.
 */
object MsgKey : KSerializer<Msg> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("me.raddatz.veyq.mobile.words.MsgKey", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Msg) = encoder.encodeString(value.key)
    override fun deserialize(decoder: Decoder): Msg {
        val key = decoder.decodeString()
        return Msg.entries.firstOrNull { it.key == key } ?: throw SerializationException("no word has the key $key")
    }
}

/**
 * A [Notice] on the wire: its key, its kind and each argument tagged by what it is, so the platform across the bridge
 * says it as a platform here does ([NoticeArg]). Its sizes, moments and amounts are written first ([Shown.args]) in the
 * language of the encoder's [WireWords.module] (English without one), so the host formats none; an argument of another
 * type is refused, never sent as its `toString`.
 */
object NoticeSerializer : KSerializer<Notice> {
    override val descriptor: SerialDescriptor = NoticeWire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Notice) =
        encoder.encodeSerializableValue(NoticeWire.serializer(), NoticeWire.of(Shown.args(value, WireWords.languageOf(encoder))))
    override fun deserialize(decoder: Decoder): Notice = decoder.decodeSerializableValue(NoticeWire.serializer()).notice()
}

/** A [Piece] on the wire: words ([PieceWire.Said]) or a string ([PieceWire.Plain]), its value written by [Shown.piece]. */
object PieceSerializer : KSerializer<Piece> {
    override val descriptor: SerialDescriptor = PieceWire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Piece) =
        encoder.encodeSerializableValue(PieceWire.serializer(), PieceWire.of(Shown.piece(value, WireWords.languageOf(encoder))))
    override fun deserialize(decoder: Decoder): Piece = decoder.decodeSerializableValue(PieceWire.serializer()).piece()
}

@Serializable @SerialName("Piece")
internal sealed class PieceWire {
    abstract fun piece(): Piece

    @Serializable @SerialName("said") class Said(val notice: Notice) : PieceWire() { override fun piece() = Piece.Said(notice) }
    @Serializable @SerialName("plain") class Plain(val text: String) : PieceWire() { override fun piece() = Piece.Plain(text) }

    companion object {
        fun of(p: Piece): PieceWire = when (p) {
            is Piece.Said -> Said(p.notice)
            is Piece.Plain -> Plain(p.text)
            else -> throw SerializationException("a piece's value is written before the wire: $p")
        }
    }
}

@Serializable @SerialName("Notice")
internal class NoticeWire(@Serializable(with = MsgKey::class) val msg: Msg, val args: List<NoticeArg>, val kind: NoticeKind) {
    fun notice() = Notice(msg, args.map { it.arg() }, kind)

    companion object {
        fun of(n: Notice) = NoticeWire(n.msg, n.args.map(NoticeArg::of), n.kind)
    }
}

/** One argument of a [Notice] as the wire carries it, by its own tag: a value is written as its text first ([Shown.args]). */
@Serializable @SerialName("NoticeArg")
internal sealed class NoticeArg {
    abstract fun arg(): Any

    @Serializable @SerialName("text") class Text(val text: String) : NoticeArg() { override fun arg() = text }
    @Serializable @SerialName("int") class IntArg(val value: Int) : NoticeArg() { override fun arg() = value }
    @Serializable @SerialName("long") class LongArg(val value: Long) : NoticeArg() { override fun arg() = value }
    @Serializable @SerialName("notice") class Said(val notice: Notice) : NoticeArg() { override fun arg() = notice }
    @Serializable @SerialName("joined") class JoinedArg(val parts: List<NoticeArg>, val separator: String) : NoticeArg() {
        override fun arg() = Joined(parts.map { it.arg() }, separator)
    }

    companion object {
        fun of(a: Any): NoticeArg = when (a) {
            is String -> Text(a)
            is Int -> IntArg(a)
            is Long -> LongArg(a)
            is Notice -> Said(a)
            is Joined -> JoinedArg(a.parts.map(::of), a.separator)
            else -> throw SerializationException("a notice's argument of ${a::class.simpleName} has no form on the wire")
        }
    }
}

/**
 * Which notice each outcome raises — the one table both apps used to write twice (the web's words where it has
 * them): null where the outcome needs none (it went through).
 */
object Notices {
    /** [msg] alone, without arguments (Swift reaches a constructor's vararg only as a Kotlin array). */
    fun of(msg: Msg): Notice = Notice(msg)

    /** A node moved to the trash ([NodeTrash.Deleted.TRASHED], offered its undo), else why not. */
    fun trashed(outcome: NodeTrash.Deleted, name: String): Notice = when (outcome) {
        NodeTrash.Deleted.TRASHED -> Notice(Msg.TRASH_MOVED, name)
        NodeTrash.Deleted.GONE -> Notice(Msg.DELETE_REFUSED_GONE)
        NodeTrash.Deleted.SCOPE_DELETING -> Notice(Msg.DELETE_REFUSED_SCOPE_DELETING)
        NodeTrash.Deleted.FAILED -> Notice(Msg.DELETE_REFUSED_FAILED)
    }

    /** The undo of a trash did not bring [name] back. */
    fun trashUndoFailed(name: String) = Notice(Msg.TRASH_UNDO_FAILED, name)

    /** Why a move did not go through (`node.move.refused.*`). */
    fun moveRefused(outcome: NodeMove.Outcome): Notice? = when (outcome) {
        NodeMove.Outcome.MOVED -> null
        NodeMove.Outcome.NAME_TAKEN -> Notice(Msg.MOVE_REFUSED_SLUG_TAKEN)
        NodeMove.Outcome.NOT_ALLOWED, NodeMove.Outcome.BLOCKED -> Notice(Msg.MOVE_REFUSED_ILLEGAL_STRUCTURE)
        NodeMove.Outcome.GONE -> Notice(Msg.MOVE_REFUSED_GONE)
        NodeMove.Outcome.CONFLICT -> Notice(Msg.MOVE_REFUSED_STALE_PLACEMENT)
        NodeMove.Outcome.SCOPE_DELETING -> Notice(Msg.MOVE_REFUSED_SCOPE_DELETING)
        NodeMove.Outcome.BUSY -> Notice(Msg.MOVE_REFUSED_REKEY_PENDING)
        else -> Notice(Msg.MOVE_REFUSED_FAILED)
    }

    /** Why a rename did not land (`nodeNameErrorText`): a sibling holding the name is named with its kind's noun. */
    fun renameRefused(r: NodeRename.Result): Notice? = when (r.outcome) {
        NodeRename.Outcome.RENAMED -> null
        NodeRename.Outcome.NAME_TAKEN -> r.taken?.let { sibling ->
            Notice(Msg.NODE_NAME_TAKEN_BY, Notice(me.raddatz.veyq.mobile.AppKinds.noun(sibling.kind) ?: Msg.NODE_AN_ITEM), sibling.name)
        } ?: Notice(Msg.NODE_SLUG_TAKEN)
        NodeRename.Outcome.NOT_ALLOWED -> Notice(Msg.NODE_ILLEGAL_STRUCTURE)
        NodeRename.Outcome.BUSY -> Notice(Msg.POT_BUSY)
        NodeRename.Outcome.FAILED -> Notice(Msg.NODE_SAVE_FAILED)
    }

    /** Why a folder's (or a file's) copy did not go through; null [outcome] is a copy that threw. */
    fun subtreeCopyRefused(outcome: SubtreeCopy.Outcome?): Notice? = when (outcome) {
        SubtreeCopy.Outcome.COPIED -> null
        SubtreeCopy.Outcome.NAME_TAKEN -> Notice(Msg.COPY_NAME_TAKEN)
        SubtreeCopy.Outcome.NOTHING_TO_COPY -> Notice(Msg.COPY_NOTHING)
        else -> Notice(Msg.COPY_FAILED_ROLLED_BACK)
    }

    /** Why a new folder was not made; null [reason] is a create that threw. */
    fun folderRefused(reason: NodeCreate.Reason?): Notice =
        if (reason == NodeCreate.Reason.NAME_TAKEN) Notice(Msg.FOLDER_NAME_TAKEN) else Notice(Msg.FOLDER_FAILED)

    /** Why a report did not reach the moderators. */
    fun reportRefused(outcome: NodeReport.Outcome): Notice? = when (outcome) {
        NodeReport.Outcome.SENT -> null
        NodeReport.Outcome.TOO_MANY_OPEN -> Notice(Msg.REPORT_TOO_MANY_OPEN)
        NodeReport.Outcome.RATE_LIMITED -> Notice(Msg.REPORT_RATE_LIMITED)
        NodeReport.Outcome.UNAVAILABLE -> Notice(Msg.REPORT_UNAVAILABLE)
        NodeReport.Outcome.NOT_FOUND -> Notice(Msg.REPORT_NOT_FOUND)
        else -> Notice(Msg.REPORT_FAILED)
    }

    /** What a link's change (edit, renew, switch off or on, delete) left to say. */
    fun linkChanged(outcome: ShareLinkChanges.Outcome): Notice? = when (outcome) {
        ShareLinkChanges.Outcome.DONE -> null
        ShareLinkChanges.Outcome.DONE_REKEY_PENDING -> Notice(Msg.SHARE_CLOSED_REKEYING)
        ShareLinkChanges.Outcome.PASSWORD_LOST -> Notice(Msg.SHARE_PASSWORD_LOST)
        ShareLinkChanges.Outcome.GONE -> Notice(Msg.SHARE_GONE)
        ShareLinkChanges.Outcome.RULES -> Notice(Msg.SHARE_REQUIREMENT_REFUSED)
        ShareLinkChanges.Outcome.REISSUE_REQUIRED -> Notice(Msg.SHARE_REISSUE_HINT)
        else -> Notice(Msg.SHARE_FAILED)
    }

    /** Why a new link was not made. */
    /** A node a download or a copy left out, with why (the web's `export.reason.*`, `node.copy.reason.*`). */
    fun leftOut(s: me.raddatz.veyq.mobile.export.NodeExport.Skipped): Notice = Notice(
        Msg.LEFT_OUT_LINE, s.name,
        Notice(
            when (s.reason) {
                me.raddatz.veyq.mobile.export.NodeExport.NOTHING_TO_EXPORT -> Msg.LEFT_OUT_NOTHING
                me.raddatz.veyq.mobile.export.SubtreeCopy.NOT_COPYABLE -> Msg.LEFT_OUT_NOT_COPYABLE
                else -> Msg.LEFT_OUT_UNSUPPORTED
            },
        ),
    )

    /** What a download of [name] says once it ended: downloaded, or (null [leftOut]: it did not finish) failed. */
    fun downloaded(name: String, leftOut: List<me.raddatz.veyq.mobile.export.NodeExport.Skipped>?): Notice =
        Notice(if (leftOut == null) Msg.DOWNLOAD_FAILED else Msg.DOWNLOAD_DONE, name)

    /** What a download left out, one line ([leftOut] each, joined); null where it left out nothing. */
    fun downloadLeftOut(leftOut: List<me.raddatz.veyq.mobile.export.NodeExport.Skipped>?): Notice? =
        leftOut?.takeIf { it.isNotEmpty() }?.let { Notice(Msg.DOWNLOAD_LEFT_OUT, Joined(it.map(::leftOut), "; ")) }

    /** What switching a link off says: nothing once closed, that its re-key is still due, or that it failed. */
    fun linkClosed(closed: ShareLinks.Closed): Notice? = when (closed) {
        ShareLinks.Closed.CLOSED -> null
        ShareLinks.Closed.CLOSED_REKEY_PENDING -> Notice(Msg.SHARE_CLOSED_REKEYING)
        ShareLinks.Closed.FAILED -> Notice(Msg.SHARE_FAILED)
    }

    fun linkRefused(outcome: ShareLinks.Outcome): Notice? = when (outcome) {
        ShareLinks.Outcome.CREATED -> null
        ShareLinks.Outcome.PASSWORD_REQUIRED, ShareLinks.Outcome.EXPIRY_REJECTED -> Notice(Msg.SHARE_REQUIREMENT_REFUSED)
        ShareLinks.Outcome.NOT_ALLOWED -> Notice(Msg.SHARE_NOT_ALLOWED)
        ShareLinks.Outcome.SCOPE_DELETING -> Notice(Msg.DELETE_REFUSED_SCOPE_DELETING)
        ShareLinks.Outcome.BUSY -> Notice(Msg.MOVE_REFUSED_REKEY_PENDING)
        else -> Notice(Msg.SHARE_FAILED)
    }

    /**
     * What a picked or dropped upload says of the [names] whose paths did not read (no regular file there, or none that
     * opens): the one by its name, several by their count; null where every path read. Never silent: a drop the core
     * queues nothing for would otherwise look taken (the packaged Windows app, 2026-11-13).
     */
    fun notRead(names: List<String>): Notice? = when (names.size) {
        0 -> null
        1 -> Notice(Msg.DEPOT_FOLDER_UNREADABLE, names.single())
        else -> Notice(Msg.DEPOT_FILES_UNREADABLE, names.size)
    }

    /** Why an upload item did not go up (the line under it in the uploads list); null while it may still. */
    fun uploadProblem(item: UploadQueue.Item): Notice? {
        if (item.status != UploadQueue.Status.FAILED) return null
        return when (item.error) {
            UploadQueue.QUOTA_EXCEEDED -> Notice(Msg.UPLOAD_NO_SPACE)
            UploadQueue.APPLICATION_OFF -> Notice(Msg.UPLOAD_SWITCHED_OFF)
            UploadQueue.NAME_TAKEN -> Notice(Msg.UPLOAD_NAME_TAKEN)
            UploadQueue.BUSY -> Notice(Msg.MOVE_REFUSED_REKEY_PENDING)
            else -> Notice(Msg.UPLOAD_STATUS_FAILED)
        }
    }

    /** An upload item's state, for the uploads list ([uploadProblem] says a failure's why). */
    fun uploadStatus(item: UploadQueue.Item): Notice = when (item.status) {
        UploadQueue.Status.QUEUED -> Notice(Msg.UPLOAD_STATUS_QUEUED)
        UploadQueue.Status.PREPARING -> Notice(Msg.UPLOAD_STATUS_PREPARING)
        UploadQueue.Status.UPLOADING -> Notice(Msg.UPLOAD_STATUS_UPLOADING)
        UploadQueue.Status.DONE -> Notice(Msg.UPLOAD_STATUS_DONE)
        UploadQueue.Status.FAILED -> uploadProblem(item) ?: Notice(Msg.UPLOAD_STATUS_FAILED)
        UploadQueue.Status.CANCELLED -> Notice(Msg.UPLOAD_STATUS_CANCELLED)
    }

    /**
     * The uploads' one line (the web's `UploadStatusMenu`, what both phones' trays say): how many are under way while any
     * is, else how many did not go up, else that all is in; null while the queue lists nothing.
     */
    fun uploadSummary(q: UploadQueue.State): Notice? = when {
        q.batches.isEmpty() -> null
        q.active > 0 -> Notice(Msg.UPLOAD_ACTIVE, q.active)
        q.failed > 0 -> Notice(Msg.UPLOAD_FAILED_COUNT, q.failed)
        else -> Notice(Msg.UPLOAD_STATUS_DONE)
    }
}

/**
 * The language a bridge's wire writes a notice's and a line's values in ([Shown.args], [Shown.piece]): [module] names it to
 * [NoticeSerializer] and [PieceSerializer] through the encoder's serializers module (the bridge's `Config.language`).
 * Beside the serializers, never in [Shown], which the web's façades may reach without kotlinx.serialization.
 */
object WireWords {
    /** A serializers module naming [language]; the bridge's `Json` carries it. */
    fun module(language: String): SerializersModule = SerializersModule { contextual(Marker::class, Marker.In(language)) }

    /** The language [encoder]'s [module] names; English without one. */
    internal fun languageOf(encoder: Encoder): String = (encoder.serializersModule.getContextual(Marker::class) as? Marker.In)?.language ?: "en"

    /** What [module] registers: its serializer carries the language, nothing of it is ever written. */
    internal class Marker private constructor() {
        class In(val language: String) : KSerializer<Marker> {
            override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("me.raddatz.veyq.mobile.words.WireWords", PrimitiveKind.STRING)
            override fun serialize(encoder: Encoder, value: Marker) = encoder.encodeString(language)
            override fun deserialize(decoder: Decoder): Marker = throw SerializationException("never read")
        }
    }
}

/**
 * A value a view shows — a size, a day, a moment, a Memory's days, an amount, a length —, carried over a bridge as the
 * text [Shown] writes for it in the language of the encoder's [WireWords.module] (ADR 0154 decision 5): a bridge host
 * reads a string and formats none. Beside a raw value a host still needs for itself (a calendar's day, a scale's range),
 * or in place of one it only showed. Read back (a test's round trip) it is the text it was written as.
 */
@Serializable(with = ShownTextSerializer::class)
class ShownText private constructor(private val write: (language: String) -> String) {
    /** The text in [language] (`en`, `de`). */
    fun text(language: String): String = write(language)

    override fun equals(other: Any?) = other is ShownText && other.text("en") == text("en") && other.text("de") == text("de")
    override fun hashCode() = text("en").hashCode()
    override fun toString() = "ShownText(${text("en")})"

    companion object {
        fun size(bytes: Long) = ShownText { Shown.size(bytes, it) }
        fun day(iso: String) = ShownText { Shown.day(iso, it) }
        fun day(epochDay: Long) = ShownText { Shown.day(epochDay, it) }
        fun dayOf(instant: String) = ShownText { Shown.dayOf(instant, it) }
        fun moment(epochMs: Long) = ShownText { Shown.moment(epochMs, it) }
        fun moment(instant: String) = ShownText { Shown.moment(instant, it) }
        fun memoryDay(iso: String) = ShownText { Shown.memoryDay(iso, it) }
        fun dayRange(from: String, to: String?) = ShownText { Shown.dayRange(from, to, it) }
        fun money(amount: Long, currency: String?, minorUnits: Int) = ShownText { Shown.money(amount, currency, minorUnits, it) }
        fun length(ms: Long) = ShownText { Shown.length(ms) }
        fun distance(value: Double, kilometres: Boolean) = ShownText { Shown.distance(value, kilometres, it) }
        fun written(text: String) = ShownText { text }
    }
}

/** [ShownText] on the wire: its text in the encoder's language, a plain string. */
object ShownTextSerializer : KSerializer<ShownText> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("me.raddatz.veyq.mobile.words.ShownText", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ShownText) = encoder.encodeString(value.text(WireWords.languageOf(encoder)))
    override fun deserialize(decoder: Decoder): ShownText = ShownText.written(decoder.decodeString())
}
