package me.raddatz.veyq.mobile.expenses

/**
 * The entry editor's form and its translation to and from the format v1 body, a port of
 * `src/web/src/features/expenses/entryForm.ts`: what a person types — amounts as text, a typed rate, the payers and the
 * items with their splits — and the exact content a write seals. Pure, so neither platform's editor holds a rule of its
 * own: what may be saved is decided here and by [Ledger.entryProblem], which the write path asks again before sealing.
 * What cannot be saved names the field it is at ([Refusal.at]), so the editor says it there.
 *
 * An expense's (a refund's) amount is never typed: it is the sum of what its payers (receivers) paid. Its allocation is
 * the items, each an exact amount with its own split, and the rest — the amount less the items — by a split of its own; a
 * new entry has no items, so the whole amount is rest, split equally among everyone offered. Shares of 1 each are sealed
 * as the format's `equal` split, so "equally" is no mode of the editor's.
 */
data class EntryForm(
    val kind: Kind,
    val title: String,
    /** `YYYY-MM-DD`. */
    val date: String,
    val category: String,
    val currency: String,
    /** The currency's minor-unit digits; the entry seals them. */
    val minorUnits: Int,
    /** The typed rate to the base currency (`1` while the currency is the base). */
    val rate: String,
    /** A transfer's amount, as typed. An expense's or a refund's is the sum of its payers' rows ([payersTotal]). */
    val amount: String,
    /** An expense's payers or a refund's receivers, each with the amount they paid or received. */
    val payers: List<Payer>,
    val items: List<FormItem>,
    /** The split of the rest (the amount less the items), sealed only while the rest is above zero. */
    val rest: FormSplit,
    /** A transfer's two ends. */
    val from: String,
    val to: String,
    /** The receipt ids the revision lists. */
    val receipts: List<String> = emptyList(),
    val refunds: String? = null,
) {
    enum class Kind { EXPENSE, REFUND, TRANSFER }

    /** The editor's split modes; shares of 1 each are sealed as `equal`. */
    enum class Mode { SHARES, PERCENT, AMOUNTS }

    data class Payer(val participant: String, val amount: String)

    /** One person of a split as typed, with what was typed for them in each mode: switching modes loses nothing. */
    data class SplitRow(val participant: String, val shares: String = "1", val percent: String = "", val amount: String = "")

    /** A split as typed. [expanded] false: the editor shows it as one line, "everyone, equally"; nothing of it is sealed. */
    data class FormSplit(val mode: Mode, val rows: List<SplitRow>, val expanded: Boolean = false)

    data class FormItem(val label: String, val amount: String, val split: FormSplit)

    /**
     * Why the form cannot be saved: [problem] a field's key (`title`, `date`, `amount`, `rate`, `payers`, `split`,
     * `split-shares`, `split-percents`, `split-amounts`, `items`, `transfer`) or a ledger rule's ([Ledger.Problem] in the
     * web's spelling, `payers-unbalanced`); [at] the field it is at (`title`, `date`, `rate`, `amount`, `payers`,
     * `transfer`, `items`, `rest`, or `item` with [item] its index), null for none.
     */
    data class Refusal(val problem: String, val at: String? = null, val item: Int? = null)

    /** What [contentOf] answers: the content a write seals, or why there is none. */
    data class Result(val content: MoneyContent?, val refusal: Refusal?)

    companion object {
        private const val SAFE = RevisionFormat.MAX_SAFE

        fun equalSplit(people: List<String>, expanded: Boolean = false) = FormSplit(Mode.SHARES, people.map { SplitRow(it) }, expanded)

        /** [split] with every one of [everyone] on it at 1 share and no one else; what was typed otherwise is kept. */
        fun everyoneEqually(split: FormSplit, everyone: List<String>) =
            FormSplit(Mode.SHARES, everyone.map { id -> (split.rows.firstOrNull { it.participant == id } ?: SplitRow(id)).copy(shares = "1") }, true)

        /** A new entry's form: an expense in the base currency on [date], paid by [payer], no items, split equally. */
        fun empty(base: Ledger.BaseCurrency, people: List<String>, date: String, payer: String? = people.firstOrNull()) = EntryForm(
            Kind.EXPENSE, "", date, "other", base.currency, base.minorUnits, "1", "",
            listOf(Payer(payer ?: "", "")), emptyList(), equalSplit(people),
            payer ?: "", people.firstOrNull { it != payer } ?: "",
        )

        fun emptyItem(people: List<String>) = FormItem("", "", equalSplit(people))

        /** Basis points as a percent is typed: 3333 → `33.33`. */
        fun percentText(basisPoints: Long, separator: Char = '.') = Amounts.text(basisPoints, 2, separator)

        private fun samePeople(a: List<String>, b: List<String>) = a.size == b.size && a.toSet().size == a.size && a.all { it in b }

        private fun formSplitOf(s: Split, people: List<String>, minorUnits: Int, sep: Char): FormSplit = when (s) {
            is Split.Equal -> equalSplit(s.participants, !samePeople(s.participants, people))
            is Split.Shares -> FormSplit(Mode.SHARES, s.shares.map { SplitRow(it.first, shares = it.second.toString()) }, true)
            is Split.Percent -> FormSplit(Mode.PERCENT, s.percents.map { SplitRow(it.first, percent = percentText(it.second, sep)) }, true)
            is Split.Amounts -> FormSplit(Mode.AMOUNTS, s.amounts.map { SplitRow(it.first, amount = Amounts.text(it.second, minorUnits, sep)) }, true)
        }

        /** The form of an existing entry's [content], to edit it; [people] those it offers ([PotView.offeredPeople]). */
        fun of(content: MoneyContent, base: Ledger.BaseCurrency, people: List<String>, separator: Char = '.'): EntryForm {
            val mu = content.minorUnits
            val form = empty(base, people, content.date).copy(
                currency = content.currency, minorUnits = mu, rate = content.rate,
                amount = Amounts.text(content.amount, mu, separator), receipts = content.receipts,
            )
            return when (content) {
                is Transfer -> form.copy(kind = Kind.TRANSFER, title = content.title ?: "", from = content.from, to = content.to)
                is Expense -> withAllocation(form.copy(kind = Kind.EXPENSE, title = content.title, category = content.category), content.payers, content.allocation, people, separator)
                is Refund -> withAllocation(
                    form.copy(kind = Kind.REFUND, title = content.title, category = content.category, refunds = content.refunds),
                    content.receivers, content.allocation, people, separator,
                )
            }
        }

        private fun withAllocation(form: EntryForm, contributors: List<Contribution>, a: Allocation, people: List<String>, sep: Char): EntryForm {
            val mu = form.minorUnits
            return form.copy(
                payers = contributors.map { Payer(it.participant, Amounts.text(it.amount, mu, sep)) },
                items = a.items.map { FormItem(it.label, Amounts.text(it.amount, mu, sep), formSplitOf(it.split, people, mu, sep)) },
                rest = a.rest?.let { formSplitOf(it, people, mu, sep) } ?: form.rest,
            )
        }

        /** A typed percent in basis points (`33,33` → 3333), or null past two decimals, at zero or above 100 %. */
        fun parsePercent(typed: String): Long? = Amounts.parse(typed, 2)?.takeIf { it <= RevisionFormat.PERCENT_WHOLE }

        /** The sum of typed amounts that parse, in minor units; one that does not parse counts nothing. */
        fun typedSum(texts: List<String>, minorUnits: Int): Long {
            var total = 0L
            for (t in texts) {
                val v = Amounts.parse(t, minorUnits) ?: continue
                if (total + v <= SAFE) total += v
            }
            return total
        }

        /** The basis points a percent split's typed values add up to (rows that name a person). */
        fun percentTotal(split: FormSplit): Long = split.rows.filter { it.participant.isNotEmpty() }.sumOf { parsePercent(it.percent) ?: 0L }

        /** The minor units an amounts split's typed values add up to (rows that name a person). */
        fun amountsTotal(split: FormSplit, minorUnits: Int): Long = typedSum(split.rows.filter { it.participant.isNotEmpty() }.map { it.amount }, minorUnits)

        /** A ledger problem in the web's spelling (`PAYERS_UNBALANCED` → `payers-unbalanced`). */
        fun key(p: Ledger.Problem): String = p.name.lowercase().replace('_', '-')
    }

    /** An expense's (a refund's) amount as the editor shows it: the sum of its payers' typed amounts. */
    val payersTotal: Long get() = typedSum(payers.map { it.amount }, minorUnits)

    /** What is left for the rest: the payers' total less the typed items (negative: the items are above the amount). */
    val restLeft: Long get() = payersTotal - typedSum(items.map { it.amount }, minorUnits)

    /** An item's typed amount in minor units (0 while it is none): what an amounts split of it must add up to. */
    fun itemAmount(item: FormItem): Long = Amounts.parse(item.amount, minorUnits) ?: 0

    /** The form in [currency]: its digits (the base's for the base, else [minorUnits]), the rate `1` for the base and left
     *  to be typed for any other. An entry being edited keeps its digits while its currency stays. */
    fun withCurrency(currency: String, minorUnits: Int, base: Ledger.BaseCurrency): EntryForm {
        if (currency == this.currency) return this
        val digits = if (currency == base.currency) base.minorUnits else minorUnits
        return copy(currency = currency, minorUnits = digits, rate = if (currency == base.currency) "1" else "")
    }

    /** The form switched to [kind], the amount carried across: a transfer's empty amount takes the payers' total, and a
     *  lone payer with no amount takes the transfer's. */
    fun withKind(kind: Kind, separator: Char = '.'): EntryForm {
        if (kind == this.kind) return this
        var next = copy(kind = kind)
        if (kind == Kind.TRANSFER && amount.isBlank()) {
            if (payersTotal > 0) next = next.copy(amount = Amounts.text(payersTotal, minorUnits, separator))
        } else if (kind != Kind.TRANSFER && this.kind == Kind.TRANSFER && payers.size == 1 && payers[0].amount.isBlank()) {
            next = next.copy(payers = listOf(payers[0].copy(amount = amount)))
        }
        return next
    }

    private sealed interface SplitAnswer {
        data class Sealed(val split: Split) : SplitAnswer
        data class Refused(val problem: String) : SplitAnswer
    }

    private fun splitOf(s: FormSplit): SplitAnswer {
        val rows = s.rows.filter { it.participant.isNotEmpty() }
        if (rows.isEmpty()) return SplitAnswer.Refused("empty")
        return when (s.mode) {
            Mode.SHARES -> {
                val shares = ArrayList<Pair<String, Long>>()
                for (r in rows) {
                    val typed = r.shares.trim()
                    val n = typed.takeIf { Regex("^[0-9]+$").matches(it) }?.toLongOrNull()
                    if (n == null || n < 1 || n > 1_000_000) return SplitAnswer.Refused("split-shares")
                    shares += r.participant to n
                }
                if (shares.all { it.second == 1L }) SplitAnswer.Sealed(Split.Equal(shares.map { it.first })) else SplitAnswer.Sealed(Split.Shares(shares))
            }
            Mode.PERCENT -> {
                val percents = ArrayList<Pair<String, Long>>()
                for (r in rows) percents += r.participant to (parsePercent(r.percent) ?: return SplitAnswer.Refused("split-percents"))
                SplitAnswer.Sealed(Split.Percent(percents))
            }
            Mode.AMOUNTS -> {
                val amounts = ArrayList<Pair<String, Long>>()
                for (r in rows) amounts += r.participant to (Amounts.parse(r.amount, minorUnits) ?: return SplitAnswer.Refused("split-amounts"))
                SplitAnswer.Sealed(Split.Amounts(amounts))
            }
        }
    }

    /** The body of the form, or the first thing wrong with it and where. [isParticipant] is the Pot's. */
    fun contentOf(isParticipant: ((String) -> Boolean)? = null): Result {
        fun refuse(problem: String, at: String? = null, item: Int? = null) = Result(null, Refusal(problem, at, item))
        val rate = Amounts.parseRate(this.rate)
        val title = this.title.trim()
        val dated = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(date)
        val content: MoneyContent
        if (kind == Kind.TRANSFER) {
            val amount = Amounts.parse(this.amount, minorUnits) ?: return refuse("amount", "amount")
            if (rate == null) return refuse("rate", "rate")
            if (!dated) return refuse("date", "date")
            if (from.isEmpty() || to.isEmpty() || from == to) return refuse("transfer", "transfer")
            content = Transfer(title.ifEmpty { null }, date, currency, minorUnits, rate, amount, receipts, from, to)
        } else {
            val rows = payers.filter { it.participant.isNotEmpty() }
            if (rows.isEmpty()) return refuse("payers", "payers")
            val contributors = ArrayList<Contribution>()
            for (p in rows) contributors += Contribution(p.participant, Amounts.parse(p.amount, minorUnits) ?: return refuse("payers", "payers"))
            var amount = 0L
            for (c in contributors) {
                if (amount > SAFE - c.amount) return refuse("amount", "payers")
                amount += c.amount
            }
            if (rate == null) return refuse("rate", "rate")
            if (!dated) return refuse("date", "date")
            if (title.isEmpty()) return refuse("title", "title")
            val itemsSealed = ArrayList<Item>()
            for ((i, item) in items.withIndex()) {
                val v = Amounts.parse(item.amount, minorUnits)
                val label = item.label.trim()
                if (v == null || label.isEmpty()) return refuse("items", "item", i)
                when (val s = splitOf(item.split)) {
                    is SplitAnswer.Refused -> return refuse(if (s.problem == "empty") "items" else s.problem, "item", i)
                    is SplitAnswer.Sealed -> itemsSealed += Item(label, v, s.split)
                }
            }
            val restAmount = amount - itemsSealed.sumOf { it.amount }
            val allocation = if (restAmount <= 0) Allocation(itemsSealed) else when (val s = splitOf(rest)) {
                is SplitAnswer.Refused -> return refuse(if (s.problem == "empty") "split" else s.problem, "rest")
                is SplitAnswer.Sealed -> Allocation(itemsSealed, s.split)
            }
            content = if (kind == Kind.EXPENSE) {
                Expense(title, category, date, currency, minorUnits, rate, amount, receipts, contributors, allocation)
            } else {
                Refund(title, category, date, currency, minorUnits, rate, amount, receipts, contributors, allocation, refunds)
            }
        }
        val problem = Ledger.entryProblem(content, isParticipant) ?: return Result(content, null)
        val (at, item) = problemAt(content, problem, isParticipant)
        return refuse(key(problem), at, item)
    }

    /** Where in the form the ledger's [problem] with [c] is: the payers, the items, or the split that breaks the rule. */
    private fun problemAt(c: MoneyContent, problem: Ledger.Problem, isParticipant: ((String) -> Boolean)?): Pair<String?, Int?> {
        if (c is Transfer) return "transfer" to null
        val (contributors, allocation) = when (c) {
            is Expense -> c.payers.map { it.participant } to c.allocation
            is Refund -> c.receivers.map { it.participant } to c.allocation
            else -> error("unreachable")
        }
        val rest = Ledger.restOf(c.amount, allocation)
        val parts = allocation.items.mapIndexed { i, it -> Triple<String, Int?, Pair<Split, Big?>>("item", i, it.split to Big.of(it.amount)) } +
            listOfNotNull(allocation.rest?.let { Triple("rest", null, it to (if (rest.signum > 0) rest else null)) })
        fun partWhere(breaks: (Split, Big?) -> Boolean): Pair<String?, Int?> =
            parts.firstOrNull { breaks(it.third.first, it.third.second) }?.let { it.first to it.second } ?: (null to null)
        val unknown = { id: String -> isParticipant != null && !isParticipant(id) }
        return when (problem) {
            Ledger.Problem.PAYERS_UNBALANCED, Ledger.Problem.RECEIVERS_UNBALANCED -> "payers" to null
            Ledger.Problem.ITEMS_ABOVE_TOTAL -> "items" to null
            Ledger.Problem.REST_UNALLOCATED -> "rest" to null
            Ledger.Problem.NAMED_TWICE ->
                if (contributors.toSet().size != contributors.size) "payers" to null else partWhere { s, _ -> Ledger.splitBreaks(problem, s, null) }
            Ledger.Problem.PERCENT_UNBALANCED, Ledger.Problem.AMOUNTS_UNBALANCED -> partWhere { s, part -> Ledger.splitBreaks(problem, s, part) }
            Ledger.Problem.UNKNOWN_PARTICIPANT ->
                if (contributors.any(unknown)) "payers" to null else partWhere { s, _ -> Ledger.splitPeople(s).any(unknown) }
        }
    }

    /**
     * The entry's effect in the base currency, as the editor shows it under an expense or a refund: by the very
     * [Ledger.entryEffect] every balance is computed with, over the content the save would seal. [order] is the Pot's join
     * order (erased people included). Null for a transfer and while the form forms no valid entry — but for a missing
     * title, which moves no money.
     */
    fun preview(base: Ledger.BaseCurrency, order: List<String>, isParticipant: ((String) -> Boolean)? = null): Ledger.Effect? {
        if (kind == Kind.TRANSFER) return null
        val content = (if (title.isBlank()) copy(title = "-") else this).contentOf(isParticipant).content ?: return null
        return Ledger.entryEffect(content, base, Ledger.rankOf(order))
    }
}
