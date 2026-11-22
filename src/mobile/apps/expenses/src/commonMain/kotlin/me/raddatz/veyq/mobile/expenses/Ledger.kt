package me.raddatz.veyq.mobile.expenses

/**
 * What a device computes from a Pot's entries, a port of `src/web/src/features/expenses/domain/ledger.ts` (ADR 0108
 * decision 9): each entry's effect in the base currency, the balances, the settle-up and the overview. Every rule about
 * money runs on the device, the server holding only ciphertext; `contracts/expenses/v1-vectors.json` pins it.
 *
 * The unbalanced-entry rule (owner 2026-10-24): an entry whose sums do not add up is never saved ([entryProblem], which the
 * write path asks first), and one that arrives anyway counts in no balance, no overview and no settle-up, and is shown as
 * invalid — as is one naming a person who is no participant (it has no place in the join order ties are broken by).
 */
object Ledger {
    /** The Pot's base currency, from its sealed meta: what every balance is in. */
    data class BaseCurrency(val currency: String, val minorUnits: Int)

    /** A participant as its record's latest revision says; [member] decided by its FIRST revision. */
    data class Participant(val id: String, val name: String?, val active: Boolean, val joined: Long, val erased: Boolean, val member: String?)

    enum class Problem { PAYERS_UNBALANCED, RECEIVERS_UNBALANCED, ITEMS_ABOVE_TOTAL, REST_UNALLOCATED, PERCENT_UNBALANCED, AMOUNTS_UNBALANCED, NAMED_TWICE, UNKNOWN_PARTICIPANT }

    /** One entry's effect in base minor units: the converted total, what each payer paid or receiver received, each
     *  beneficiary's share (in join order). A transfer has its total only. */
    class Effect(val base: Big, val paid: Map<String, Big>, val received: Map<String, Big>, val shares: Map<String, Big>)

    enum class Status { LIVE, DELETED, INVALID, UNREADABLE }

    /** A money record as its latest revision leaves it; [recordedAt] is when the server stored its first revision held. */
    class Entry(
        val id: String, val revision: Long, val status: Status, val content: MoneyContent?, val problem: Problem?,
        val effect: Effect?, val edited: Boolean, val recordedAt: Long,
    )

    data class Settlement(val from: String, val to: String, val amount: Big)

    class Overview(
        val byCategory: Map<String, Big>,
        val byPerson: Map<String, Pair<Big, Big>>,
        val byCategoryAndPerson: Map<String, Map<String, Big>>,
    )

    class Pot(
        /** Every participant, live, deactivated or erased, in join order. */
        val participants: List<Participant>,
        val entries: List<Entry>,
        /** Positive: is owed; every participant has one and they sum to zero. */
        val balances: Map<String, Big>,
        val settleUp: List<Settlement>,
        val overview: Overview,
    )

    private val idOrder = Comparator<String> { a, b -> a.compareTo(b) }

    /** Join order: `joined`, then the record id. */
    fun joinOrder(participants: List<Pair<String, Long>>): List<String> =
        participants.sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first }).map { it.first }

    fun rankOf(order: List<String>): Map<String, Int> = order.withIndex().associate { (i, id) -> id to i }

    /** Whether split [s] breaks [rule] (`named-twice`, `percent-unbalanced`, `amounts-unbalanced`), [part] what an amounts
     *  split must add up to (null: none, a rest of zero) — where the editor says a ledger problem is. */
    fun splitBreaks(rule: Problem, s: Split, part: Big?): Boolean = when (rule) {
        Problem.NAMED_TWICE -> twice(splitPeople(s))
        Problem.PERCENT_UNBALANCED -> s is Split.Percent && s.percents.sumOf { it.second } != RevisionFormat.PERCENT_WHOLE
        Problem.AMOUNTS_UNBALANCED -> s is Split.Amounts && part != null && total(s.amounts.map { it.second }) != part
        else -> false
    }

    private fun splitWeights(s: Split): List<Pair<String, Big>> = when (s) {
        is Split.Equal -> s.participants.map { it to Big.ONE }
        is Split.Shares -> s.shares.map { it.first to Big.of(it.second) }
        is Split.Percent -> s.percents.map { it.first to Big.of(it.second) }
        is Split.Amounts -> s.amounts.map { it.first to Big.of(it.second) }
    }

    fun splitPeople(s: Split): List<String> = splitWeights(s).map { it.first }

    private fun splitsOf(a: Allocation): List<Split> = a.items.map { it.split } + listOfNotNull(a.rest)
    private fun total(xs: List<Long>): Big = xs.fold(Big.ZERO) { acc, x -> acc + Big.of(x) }
    private fun twice(ids: List<String>) = ids.toSet().size != ids.size

    /** `amount - sum(items)`: what the rest split gets (negative: items above the total). */
    fun restOf(amount: Long, allocation: Allocation): Big = Big.of(amount) - total(allocation.items.map { it.amount })

    /** Every person a money entry names. */
    fun namedParticipants(c: MoneyContent): List<String> = when (c) {
        is Transfer -> listOf(c.from, c.to)
        is Expense -> c.payers.map { it.participant } + allocationPeople(c.allocation)
        is Refund -> c.receivers.map { it.participant } + allocationPeople(c.allocation)
    }

    private fun allocationPeople(a: Allocation) = splitsOf(a).flatMap(::splitPeople).distinct()

    /** Why a money entry may not count — or be saved — or null when its sums add up. */
    fun entryProblem(c: MoneyContent, isParticipant: ((String) -> Boolean)? = null): Problem? {
        if (c !is Transfer) {
            val contributors = if (c is Expense) c.payers else (c as Refund).receivers
            val allocation = if (c is Expense) c.allocation else (c as Refund).allocation
            if (twice(contributors.map { it.participant })) return Problem.NAMED_TWICE
            if (total(contributors.map { it.amount }) != Big.of(c.amount)) return if (c is Expense) Problem.PAYERS_UNBALANCED else Problem.RECEIVERS_UNBALANCED
            val splits = splitsOf(allocation)
            if (splits.any { twice(splitPeople(it)) }) return Problem.NAMED_TWICE
            if (splits.any { it is Split.Percent && it.percents.sumOf { p -> p.second } != RevisionFormat.PERCENT_WHOLE }) return Problem.PERCENT_UNBALANCED
            val rest = restOf(c.amount, allocation)
            if (rest.signum < 0) return Problem.ITEMS_ABOVE_TOTAL
            if (rest.signum > 0 && allocation.rest == null) return Problem.REST_UNALLOCATED
            val parts = allocation.items.map { it.split to Big.of(it.amount) } +
                listOfNotNull(allocation.rest?.let { it to (if (rest.signum > 0) rest else null) })
            if (parts.any { (s, part) -> s is Split.Amounts && part != null && total(s.amounts.map { it.second }) != part }) return Problem.AMOUNTS_UNBALANCED
        }
        if (isParticipant != null && namedParticipants(c).any { !isParticipant(it) }) return Problem.UNKNOWN_PARTICIPANT
        return null
    }

    /** [amount] allocated among [people] by their weights, the people taken in join order, added to [into]. */
    private fun allocateInto(into: MutableMap<String, Big>, amount: Big, people: List<Pair<String, Big>>, rank: Map<String, Int>) {
        val ordered = people.sortedBy { rank.getValue(it.first) }
        val shares = Money.allocate(amount, ordered.map { Fraction.of(it.second) })
        ordered.forEachIndexed { i, p -> into[p.first] = (into[p.first] ?: Big.ZERO) + shares[i] }
    }

    private fun beneficiaryShares(amount: Long, allocation: Allocation, baseTotal: Big, rank: Map<String, Int>): Map<String, Big> {
        val parts = allocation.items.map { Big.of(it.amount) to it.split }.toMutableList()
        val rest = restOf(amount, allocation)
        if (rest.signum > 0) parts += rest to allocation.rest!!
        val partBase = Money.allocate(baseTotal, parts.map { Fraction.of(it.first) })
        val shares = LinkedHashMap<String, Big>()
        parts.forEachIndexed { i, p -> allocateInto(shares, partBase[i], splitWeights(p.second), rank) }
        return shares.entries.sortedBy { rank.getValue(it.key) }.associateTo(LinkedHashMap()) { it.key to it.value }
    }

    /** A valid money entry's effect in the base currency. */
    fun entryEffect(c: MoneyContent, base: BaseCurrency, rank: Map<String, Int>): Effect {
        val baseTotal = Money.convertToBase(Big.of(c.amount), c.minorUnits, c.rate, base.minorUnits)
        if (c is Transfer) return Effect(baseTotal, emptyMap(), emptyMap(), emptyMap())
        val (contributors, allocation) = when (c) {
            is Expense -> c.payers to c.allocation
            is Refund -> c.receivers to c.allocation
            else -> error("unreachable")
        }
        val given = LinkedHashMap<String, Big>()
        allocateInto(given, baseTotal, contributors.map { it.participant to Big.of(it.amount) }, rank)
        val shares = beneficiaryShares(c.amount, allocation, baseTotal, rank)
        return if (c is Expense) Effect(baseTotal, given, emptyMap(), shares) else Effect(baseTotal, emptyMap(), given, shares)
    }

    /** Every participant's balance over the counting entries. */
    fun balancesOf(participants: List<String>, entries: List<Entry>): Map<String, Big> {
        val balances = LinkedHashMap<String, Big>()
        participants.forEach { balances[it] = Big.ZERO }
        fun move(id: String, by: Big) { balances[id] = (balances[id] ?: Big.ZERO) + by }
        for (e in entries) {
            val effect = e.effect ?: continue
            val content = e.content ?: continue
            if (e.status != Status.LIVE) continue
            if (content is Transfer) {
                move(content.from, effect.base)
                move(content.to, -effect.base)
                continue
            }
            effect.paid.forEach { (id, v) -> move(id, v) }
            effect.received.forEach { (id, v) -> move(id, -v) }
            val sign = if (content is Expense) -Big.ONE else Big.ONE
            effect.shares.forEach { (id, v) -> move(id, sign * v) }
        }
        return balances
    }

    /** The settle-up, greedily: the largest debtor pays the largest creditor the smaller amount, both chosen again after
     *  every transfer, ties by join order. */
    fun settleUp(balances: Map<String, Big>, order: List<String>): List<Settlement> {
        val open = LinkedHashMap(balances.filterValues { it.signum != 0 })
        val known = order.toSet()
        val walk = order + open.keys.filter { it !in known }.sortedWith(idOrder)
        fun largest(debtor: Boolean): String? {
            var best: String? = null
            var most = Big.ZERO
            for (id in walk) {
                val v = open[id] ?: continue
                val amount = if (debtor) -v else v
                if (amount > most) { best = id; most = amount }
            }
            return best
        }
        val transfers = mutableListOf<Settlement>()
        while (true) {
            val debtor = largest(debtor = true) ?: break
            val creditor = largest(debtor = false) ?: break
            val owed = -open.getValue(debtor)
            val due = open.getValue(creditor)
            val amount = if (owed < due) owed else due
            transfers += Settlement(debtor, creditor, amount)
            fun settle(id: String, v: Big) { if (v.signum == 0) open.remove(id) else open[id] = v }
            settle(debtor, -owed + amount)
            settle(creditor, due - amount)
        }
        return transfers
    }

    /** The overviews over the counting expenses and refunds (transfers are not costs). */
    fun overviewOf(entries: List<Entry>): Overview {
        val byCategory = LinkedHashMap<String, Big>()
        val paid = LinkedHashMap<String, Big>()
        val share = LinkedHashMap<String, Big>()
        val people = LinkedHashSet<String>()
        val byCategoryAndPerson = LinkedHashMap<String, LinkedHashMap<String, Big>>()
        for (e in entries) {
            val content = e.content ?: continue
            val effect = e.effect ?: continue
            if (e.status != Status.LIVE || content is Transfer) continue
            val (category, sign) = when (content) {
                is Expense -> content.category to Big.ONE
                is Refund -> content.category to -Big.ONE
                else -> continue
            }
            byCategory[category] = (byCategory[category] ?: Big.ZERO) + sign * effect.base
            for ((id, v) in if (content is Expense) effect.paid else effect.received) {
                people += id
                paid[id] = (paid[id] ?: Big.ZERO) + sign * v
            }
            val cat = byCategoryAndPerson.getOrPut(category) { LinkedHashMap() }
            for ((id, v) in effect.shares) {
                people += id
                share[id] = (share[id] ?: Big.ZERO) + sign * v
                cat[id] = (cat[id] ?: Big.ZERO) + sign * v
            }
        }
        val byPerson = people.associateWith { (paid[it] ?: Big.ZERO) to (share[it] ?: Big.ZERO) }
        return Overview(byCategory, byPerson, byCategoryAndPerson)
    }

    /**
     * The Pot's ledger from every revision held: each record's state is its LATEST revision's, and the balances, the
     * settle-up and the overviews count the live, valid entries only. A record whose latest revision does not open counts
     * nowhere — falling back to an earlier one would count what the server chose to serve.
     */
    fun build(held: List<HeldRevision>, base: BaseCurrency): Pot {
        val participants = mutableListOf<Participant>()
        val money = mutableListOf<Pair<String, List<HeldRevision>>>()
        for ((id, revisions) in Revisions.byRecord(held)) {
            when (val latest = revisions.last().body?.content) {
                is ParticipantBody -> participants += Participant(id, latest.name, latest.active, latest.joined, false, memberOf(revisions))
                is Erasure -> participants += Participant(id, null, false, latest.joined, true, null)
                else -> money += id to revisions
            }
        }
        val order = joinOrder(participants.map { it.id to it.joined })
        val rank = rankOf(order)
        val entries = money.map { (id, revisions) ->
            val last = revisions.last()
            val edited = last.revision > 1
            val recordedAt = revisions.first().createdAt
            fun entry(status: Status, content: MoneyContent?, problem: Problem? = null, effect: Effect? = null) =
                Entry(id, last.revision, status, content, problem, effect, edited, recordedAt)
            when (val body = last.body?.content) {
                null -> entry(Status.UNREADABLE, null)
                Deleted -> entry(Status.DELETED, revisions.asReversed().firstNotNullOfOrNull { it.body?.content as? MoneyContent })
                is MoneyContent -> {
                    val problem = entryProblem(body) { it in rank }
                    if (problem != null) entry(Status.INVALID, body, problem) else entry(Status.LIVE, body, effect = entryEffect(body, base, rank))
                }
                else -> entry(Status.UNREADABLE, null)
            }
        }.sortedWith { a, b -> idOrder.compare(a.id, b.id) }
        val balances = balancesOf(order, entries)
        val byId = participants.associateBy { it.id }
        val overview = overviewOf(entries)
        val byPerson = order.filter { it in overview.byPerson }.associateWith { overview.byPerson.getValue(it) }
        return Pot(order.map { byId.getValue(it) }, entries, balances, settleUp(balances, order), Overview(overview.byCategory, byPerson, overview.byCategoryAndPerson))
    }

    /** The member a participant record is: its earliest revision that opened to a participant decides. */
    private fun memberOf(revisions: List<HeldRevision>): String? {
        for (r in revisions) (r.body?.content as? ParticipantBody)?.let { return it.member }
        return null
    }
}
