package me.raddatz.veyq.mobile.expenses

/**
 * What a Pot's three views show (ADR 0122 decision 4: an open Pot's bar is Entries, Overview, Settings, as the web's Pot
 * has them, `src/web/src/features/expenses/components/`), computed once for both platforms, which only lay it out and
 * format money. Amounts are minor units of the base currency; a name is null for an erased person (the platform says
 * "deleted person").
 */
class PotView(session: PotSession) {
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
    /** Without settings the Pot shows no balance and takes no entry (a member sets a base currency on the web). */
    val ready: Boolean = session.ledger != null

    private val ledger = session.ledger
    private val names: Map<String, String?> = ledger?.participants?.associate { it.id to it.name }.orEmpty()

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
            PersonRow(p.id, p.name, paid.toLong(), share.toLong(), (l.balances[p.id] ?: Big.ZERO).toLong(), p.active)
        }.filter { it.active || it.balance != 0L || it.paid != 0L || it.share != 0L }
        val erased = l.participants.filter { it.erased }
        val erasedBalance = erased.fold(Big.ZERO) { acc, p -> acc + (l.balances[p.id] ?: Big.ZERO) }
        rows + if (erased.isNotEmpty() && erasedBalance.signum != 0) listOf(PersonRow("erased", null, 0, 0, erasedBalance.toLong(), false)) else emptyList()
    }.orEmpty()

    val settleUp: List<SettleRow> = ledger?.settleUp.orEmpty().map { SettleRow(names[it.from], names[it.to], it.amount.toLong()) }

    val byCategory: List<CategoryRow> = ledger?.overview?.byCategory.orEmpty().map { (id, v) -> CategoryRow(id, session.meta?.ownName(id), v.toLong()) }

    val total: Long = byCategory.sumOf { it.amount }

    /** Who may be named on a new entry: the active people, in join order. */
    val people: List<Person> = ledger?.participants.orEmpty().filter { it.active && !it.erased }.map { Person(it.id, it.name ?: "") }

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
