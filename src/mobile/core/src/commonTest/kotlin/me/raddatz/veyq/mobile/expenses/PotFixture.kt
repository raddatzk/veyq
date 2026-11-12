package me.raddatz.veyq.mobile.expenses

/** A Pot's view built from revisions the test writes: Anna and Ben in euros, entries as given. */
internal object PotFixture {
    const val ANNA = "0e000000-0000-4000-8000-000000000001"
    const val BEN = "0e000000-0000-4000-8000-000000000002"
    const val DINNER = "0e000000-0000-4000-9000-000000000001"
    val BASE = Ledger.BaseCurrency("EUR", 2)

    class Rev(val id: String, val revision: Long, val content: RevisionContent?, val purgeAfter: Long? = null)

    fun expense(title: String, amount: Long, payer: String = ANNA) =
        Expense(title, "food", "2026-07-01", "EUR", 2, "1", amount, emptyList(), listOf(Contribution(payer, amount)), Allocation(emptyList(), Split.Equal(listOf(ANNA, BEN))))

    val people = listOf(Rev(ANNA, 1, ParticipantBody("Anna", true, 1)), Rev(BEN, 1, ParticipantBody("Ben", true, 2)))

    fun view(
        entries: List<Rev> = emptyList(), canWrite: Boolean = true, rates: Boolean = true,
        members: List<me.raddatz.veyq.mobile.session.Person>? = null, categories: List<Pair<String, String>> = emptyList(),
    ): PotView {
        val held = (people + entries).mapIndexed { i, r -> HeldRevision(r.id, r.revision, 1_000L * (i + 1), "", r.content?.let { RevisionBody(emptyList(), it) }, null, purgeAfter = r.purgeAfter) }
        val fixed = held.any { it.body?.content is MoneyContent }
        return PotView(
            PotView.Source(
                "Trip", PotMeta(BASE, categories), Ledger.build(held, BASE), LedgerCheck.of(held), members, emptySet(), canWrite,
                currencyFixed = fixed, held = held, ratesOffered = rates,
            ),
        )
    }
}
