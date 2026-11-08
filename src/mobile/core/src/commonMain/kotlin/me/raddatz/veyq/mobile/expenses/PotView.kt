package me.raddatz.veyq.mobile.expenses

/**
 * What a Pot's three views show (ADR 0122 decision 4: an open Pot's bar is Entries, Overview, Settings, as the web's Pot
 * has them, `src/web/src/features/expenses/components/`), computed once for both platforms, which only lay it out and
 * format money. Amounts are minor units of the base currency; a name is null for an erased person (the platform says
 * "deleted person").
 */
class PotView internal constructor(session: Source) {
    /** What a view is computed from: a session's state, or a test's. */
    internal class Source(
        val name: String, val meta: PotMeta?, val ledger: Ledger.Pot?, val check: LedgerCheck,
        val members: List<me.raddatz.veyq.mobile.session.Person>?, val named: Set<String>, val canWrite: Boolean,
        val canChangeSettings: Boolean = canWrite, val currencyFixed: Boolean = false,
    )

    constructor(session: PotSession) : this(
        Source(
            session.name, session.meta, session.ledger, session.check, session.members, session.named, session.canWrite,
            session.canChangeSettings, session.currencyFixed,
        ),
    )

    enum class Kind { EXPENSE, REFUND, TRANSFER }

    /** One entry row: its title (a transfer may have none), date, category id (null for a transfer), who paid or received
     *  (a transfer: from and to), its base amount (negative for a refund), the foreign amount where its currency is not the
     *  base, and whether it is invalid, unreadable or edited. */
    class EntryRow(
        val id: String, val kind: Kind?, val title: String?, val date: String?, val category: String?, val who: List<String>,
        val amount: Long?, val foreignAmount: Long?, val foreignCurrency: String?, val foreignMinorUnits: Int,
        val status: Ledger.Status, val edited: Boolean,
    )

    class PersonRow(val id: String, val name: String?, val paid: Long, val share: Long, val balance: Long, val active: Boolean)
    class SettleRow(val from: String?, val to: String?, val amount: Long)
    class CategoryRow(val id: String, val ownName: String?, val amount: Long)
    class Person(val id: String, val name: String)

    val name = session.name
    val currency: String? = session.meta?.base?.currency
    val minorUnits: Int = session.meta?.base?.minorUnits ?: 2
    val canWrite: Boolean = session.canWrite
    /** Without settings the Pot shows no balance and takes no entry until a member sets a base currency. */
    val ready: Boolean = session.ledger != null

    /** Whether this member may change the Pot's settings (its base currency, its own categories) — also while it has none. */
    val canChangeSettings: Boolean = session.canChangeSettings

    /** The base currency is fixed once the Pot holds any money entry, live or deleted. */
    val currencyFixed: Boolean = session.currencyFixed

    private val ledger = session.ledger
    /** The space's current members by user id; null for no read (a member participant then reads by its sealed name). */
    private val directory = session.members?.associateBy { it.userId }

    /** The name [p] reads by: a listed member's current display name, else the record's own (the sealed one); null when
     *  erased (`web/people.ts` `shownName`). */
    private fun shownName(p: Ledger.Participant): String? {
        if (p.erased) return null
        return p.member?.let { directory?.get(it)?.displayName } ?: p.name
    }

    private val names: Map<String, String?> = ledger?.participants?.associate { it.id to shownName(it) }.orEmpty()

    /** A person's name in a list, "" for an erased one (a platform's list carries no null). */
    private fun nameIn(id: String): String = names[id] ?: ""

    /** Live, invalid and unreadable entries, by date descending, then the first revision's storing time, then id. */
    val entries: List<EntryRow> = ledger?.entries.orEmpty()
        .filter { it.status != Ledger.Status.DELETED }
        .sortedWith(
            compareByDescending<Ledger.Entry> { it.content?.date ?: "" }.thenByDescending { it.recordedAt }.thenBy { it.id },
        )
        .map(::row)

    /** Every person with a balance or a cost, in join order; erased people as one row (name null). */
    val balances: List<PersonRow> = ledger?.let { l ->
        val rows = l.participants.filter { !it.erased }.map { p ->
            val (paid, share) = l.overview.byPerson[p.id] ?: (Big.ZERO to Big.ZERO)
            PersonRow(p.id, shownName(p), paid.toLong(), share.toLong(), (l.balances[p.id] ?: Big.ZERO).toLong(), p.active)
        }.filter { it.active || it.balance != 0L || it.paid != 0L || it.share != 0L }
        val erased = l.participants.filter { it.erased }
        val erasedBalance = erased.fold(Big.ZERO) { acc, p -> acc + (l.balances[p.id] ?: Big.ZERO) }
        rows + if (erased.isNotEmpty() && erasedBalance.signum != 0) listOf(PersonRow("erased", null, 0, 0, erasedBalance.toLong(), false)) else emptyList()
    }.orEmpty()

    val settleUp: List<SettleRow> = ledger?.settleUp.orEmpty().map { SettleRow(names[it.from], names[it.to], it.amount.toLong()) }

    val byCategory: List<CategoryRow> = ledger?.overview?.byCategory.orEmpty().map { (id, v) -> CategoryRow(id, session.meta?.ownName(id), v.toLong()) }

    val total: Long = byCategory.sumOf { it.amount }

    /** Who may be named on a new entry: the active people, in join order. */
    val people: List<Person> = ledger?.participants.orEmpty().filter { it.active && !it.erased }.map { Person(it.id, shownName(it) ?: "") }

    /** How a participant stands to the space's members: a name, a member the directory lists, one it lists no more
     *  (`FORMER`), or a member this device could not look up (`SEALED`). */
    enum class Standing { NAME, MEMBER, FORMER, SEALED }

    /** One person of the Settings tab's people (uc.expenses.manage-participants): their balance in short, and what may be
     *  done to them — renamed (a name only, never a member), deactivated or activated, deleted (a revision names them) or
     *  removed (none does; then they appear nowhere), the last two only once settled. */
    class PersonEntry(
        val id: String, val name: String, val standing: Standing, val active: Boolean, val balance: Long,
        val renamable: Boolean, val canToggle: Boolean, val canErase: Boolean, val named: Boolean,
    ) {
        val settled: Boolean get() = balance == 0L
    }

    private val named = session.named

    val personEntries: List<PersonEntry> = ledger?.let { l ->
        l.participants.filter { !it.erased }.map { p ->
            val balance = (l.balances[p.id] ?: Big.ZERO).toLong()
            val standing = when {
                p.member == null -> Standing.NAME
                directory == null -> Standing.SEALED
                p.member in directory -> Standing.MEMBER
                else -> Standing.FORMER
            }
            val settled = balance == 0L
            PersonEntry(
                p.id, shownName(p) ?: "", standing, p.active, balance,
                renamable = canWrite && p.member == null,
                // An unsettled person may be activated again, never deactivated.
                canToggle = canWrite && (settled || !p.active),
                canErase = canWrite && settled,
                named = p.id in named,
            )
        }
    }.orEmpty()

    /** How many deleted people the entries still show ("deleted person"): an erased one no revision names appears nowhere. */
    val deletedPeople: Int = ledger?.participants.orEmpty().count { it.erased && it.id in named }

    /** Whether someone still has a balance, so deactivating or deleting them waits for the settle-up. */
    val anyUnsettled: Boolean = personEntries.any { !it.settled }

    /** A member of the space who may be added: the space's current members not yet a participant (an erased participant
     *  names nobody any more), in the directory's order. */
    class Member(val userId: String, val name: String)

    val addableMembers: List<Member> = run {
        val taken = ledger?.participants.orEmpty().filter { !it.erased }.mapNotNull { it.member }.toSet()
        session.members.orEmpty().filter { it.userId !in taken }.map { Member(it.userId, it.displayName) }
    }

    /** A record the ledger check names (ADR 0108 decision 5): an entry by its title, a person by name, else what it was. */
    class Missing(val kind: MissingKind, val name: String)
    enum class MissingKind { ENTRY, TRANSFER, PERSON, ERASED_PERSON, UNKNOWN }

    /** What the last read found missing, altered or unreadable, by record: the notice above the Pot ("the balances may be
     *  wrong"), empty when the ledger is complete. */
    val missing: List<Missing> = session.check.entries.map { id ->
        val entry = ledger?.entries?.firstOrNull { it.id == id }
        val person = ledger?.participants?.firstOrNull { it.id == id }
        when (val c = entry?.content) {
            is Transfer -> c.title?.let { Missing(MissingKind.ENTRY, it) } ?: Missing(MissingKind.TRANSFER, "")
            is Expense -> Missing(MissingKind.ENTRY, c.title)
            is Refund -> Missing(MissingKind.ENTRY, c.title)
            null -> when {
                person == null -> Missing(MissingKind.UNKNOWN, "")
                person.erased -> Missing(MissingKind.ERASED_PERSON, "")
                else -> Missing(MissingKind.PERSON, person.name ?: "")
            }
        }
    }

    /** The Pot's own categories, after the defaults. */
    val ownCategories: List<Pair<String, String>> = session.meta?.categories.orEmpty()

    private fun row(e: Ledger.Entry): EntryRow {
        val c = e.content
        val base = e.effect?.base?.toLong()
        val foreign = c?.takeIf { it.currency != currency }
        return when (c) {
            is Expense -> EntryRow(e.id, Kind.EXPENSE, c.title, c.date, c.category, c.payers.map { nameIn(it.participant) }, base, foreign?.amount, foreign?.currency, c.minorUnits, e.status, e.edited)
            is Refund -> EntryRow(e.id, Kind.REFUND, c.title, c.date, c.category, c.receivers.map { nameIn(it.participant) }, base?.let { -it }, foreign?.amount, foreign?.currency, c.minorUnits, e.status, e.edited)
            is Transfer -> EntryRow(e.id, Kind.TRANSFER, c.title, c.date, null, listOf(nameIn(c.from), nameIn(c.to)), base, foreign?.amount, foreign?.currency, c.minorUnits, e.status, e.edited)
            null -> EntryRow(e.id, null, null, null, null, emptyList(), null, null, null, minorUnits, e.status, e.edited)
        }
    }
}

object Amounts {
    /**
     * A typed amount in minor units (`web/amounts.ts` `parseAmount`): digits with `.` or `,` and at most [minorUnits]
     * decimals, above zero and at most 2⁵³−1; null for anything else.
     */
    fun parse(text: String, minorUnits: Int): Long? {
        val t = text.trim().replace(',', '.')
        val m = Regex("^([0-9]+)(?:\\.([0-9]*))?$").matchEntire(t) ?: return null
        val frac = m.groupValues[2]
        if (frac.length > minorUnits) return null
        val digits = (m.groupValues[1] + frac.padEnd(minorUnits, '0')).trimStart('0').ifEmpty { "0" }
        if (digits.length > 16) return null
        val v = digits.toLongOrNull() ?: return null
        return v.takeIf { it in 1..RevisionFormat.MAX_SAFE }
    }
}
