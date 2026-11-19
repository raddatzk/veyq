package me.raddatz.veyq.mobile.navigation

/**
 * One kind's answer to files shared to veyq (ADR 0138 decision 2): which files it takes, its bounds for one share, where a
 * member may choose a node of it, and [how] it takes them. Each application writes its own (Expenses' `PotShare.answer`,
 * Memories' `MemoryShare.answer`); core asks `AppKinds.share(kind)` and never knows an application's rules.
 */
class ShareAnswer(
    val how: How,
    private val intake: () -> Intake,
    private val may: (Place) -> Boolean,
) {
    /** How the chosen node takes the files. */
    enum class How {
        /** Into its editor, the bytes held in memory within the answer's bounds (a Pot's new expense, ADR 0138 decision 6). */
        EDITOR,

        /** Into the app's one upload queue, each file a source read a frame at a time (a Memory, a chapter). */
        QUEUE,
    }

    /** One share's files judged in order: each one taken counts against the answer's bounds for that share. */
    fun interface Intake {
        fun take(type: String?, name: String?, size: Long): Boolean
    }

    /** Where a member looks at a node of the kind: what an answer reads to say whether it may be chosen. */
    data class Place(
        val kind: String,
        /** The applications the tenant switched off (`Account.applicationsOff`). */
        val applicationsOff: Set<String>,
        /** Whether the account reaches its server: offline nothing is written (ADR 0140 decision 7). */
        val online: Boolean,
        /** Whether the node is a guest's identity folder or lies in one (ADR 0080 decision 11). */
        val inIdentityFolder: Boolean,
    )

    /** A fresh judge for one share. */
    fun intake(): Intake = intake.invoke()

    /** Whether a member may put shared files into a node at [place]. */
    fun mayChoose(place: Place): Boolean = may(place)

    /** Shared files sorted by an answer: those it takes, in order, and those it leaves out. */
    class Sorted(val taken: List<me.raddatz.veyq.mobile.blob.UploadSource>, val left: List<me.raddatz.veyq.mobile.blob.UploadSource>)

    /** Shared [files] sorted, in order, by one [intake] (what the platforms call; [split] for any other list). */
    fun sort(files: List<me.raddatz.veyq.mobile.blob.UploadSource>): Sorted =
        split(files, { it.type }, { it.name }, { it.size }).let { (t, l) -> Sorted(t, l) }

    /** [files] split, in order, into those this answer takes (one [intake]) and the rest. */
    fun <T> split(files: List<T>, type: (T) -> String?, name: (T) -> String?, size: (T) -> Long): Pair<List<T>, List<T>> {
        val judge = intake()
        return files.partition { judge.take(type(it), name(it), size(it)) }
    }
}
