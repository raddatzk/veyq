package me.raddatz.veyq.mobile.expenses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.crypto.Naming
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * Creating a Pot (uc.expenses.create-pot, ADR 0108 decision 2), a port of `src/web/src/features/expenses/createPot.ts`:
 * core's node create with Expenses' kind and its settings sealed into the node's meta in the same request, then its
 * people appended in batches of the server's cap. The node exists before its records do: an append that fails leaves an
 * empty Pot ([Outcome.PEOPLE_NOT_ADDED]); nothing is rolled back behind the person's back.
 */
object PotCreate {
    /** The append's batch cap (`PotAppendRequest.revisions` maxItems). */
    const val BATCH = 256

    /** Expenses' route slug (`app.route('expenses')`): it prefixes the token input, so a name is unique among Expenses'
     *  siblings only — a Memory and a Pot "Rom" coexist. */
    const val ROUTE = "expenses"

    /** One person the Pot starts with: a name typed, or a member of the space with their display name as it is now. */
    class NewPerson private constructor(val name: String, val userId: String?) {
        companion object {
            fun name(name: String) = NewPerson(name.trim(), null)
            fun member(userId: String, displayName: String) = NewPerson(PotSession.cut(displayName, 100), userId)
        }
    }

    /**
     * The new Pot's first people as its sheet gathers them (the web's create dialog): a typed name once (any case), a
     * member once; a member of another space is dropped when the space changes, and members already in are not offered.
     */
    class People private constructor(val list: List<NewPerson>) {
        constructor() : this(emptyList())

        private fun key(p: NewPerson) = p.userId?.let { "member:$it" } ?: "name:${p.name.lowercase()}"

        fun add(p: NewPerson): People = if (list.any { key(it) == key(p) }) this else People(list + p)

        /** A typed name added (trimmed); nothing for a blank one. */
        fun typed(text: String): People = text.trim().takeIf { it.isNotEmpty() }?.let { add(NewPerson.name(it)) } ?: this

        fun remove(p: NewPerson): People = People(list.filterNot { key(it) == key(p) })

        /** The people left once [members] are the chosen space's: a member of another space goes. */
        fun keep(members: List<me.raddatz.veyq.mobile.session.Person>): People =
            People(list.filter { p -> p.userId == null || members.any { it.userId == p.userId } })

        /** The space's members still to offer: those not added yet. */
        fun offered(members: List<me.raddatz.veyq.mobile.session.Person>): List<me.raddatz.veyq.mobile.session.Person> =
            members.filter { m -> list.none { it.userId == m.userId } }
    }

    /** The space a new Pot's sheet starts on: the one shown, else the first. */
    fun defaultSpace(shell: me.raddatz.veyq.mobile.navigation.Shell, spaces: List<me.raddatz.veyq.mobile.navigation.SpaceRef>): String? =
        shell.space ?: spaces.firstOrNull()?.scopeId

    /** Where a new Pot lands in [scopeId]: the folder shown where that is the space shown (the web's "New" at a level), else its root. */
    fun parentIn(shell: me.raddatz.veyq.mobile.navigation.Shell, scopeId: String): String? =
        if (scopeId == shell.space) shell.folder?.nodeId else null

    /**
     * The base currency a new Pot starts with: the euro for a German-speaking person, else the dollar (the web's).
     * [language] is a language ("de") or a locale's tag whose first part is one ("de-DE", a desktop's "de_DE.UTF-8").
     */
    fun defaultCurrency(language: String): String =
        if (language.substringBefore('-').substringBefore('_').lowercase() == "de") "EUR" else "USD"

    enum class Outcome { CREATED, PEOPLE_NOT_ADDED, INVALID, NAME_TAKEN, BUSY, BLOCKED, REFUSED }

    /** What a create came to: the new Pot's node id where it exists. */
    class Result(val outcome: Outcome, val nodeId: String? = null)

    /** The token input of a Pot named [name] (`registry.tokenInputFor`): its route slug, then the name slugified. */
    fun tokenInput(name: String): String = "$ROUTE/${Naming.slugify(name)}"

    /**
     * Creates a Pot named [name] in [scopeId] under [parentId] (null: the space's root) with [base], and its [people] in
     * the order given (each joins a millisecond after the one before: the join order is the order they were added in).
     * [categories] are the Pot's own beside the default set (a copy carries the original's).
     */
    @Throws(Exception::class)
    suspend fun create(
        tenant: TenantSession, scopeId: String, parentId: String?, name: String, base: Ledger.BaseCurrency, people: List<NewPerson>,
        categories: List<Pair<String, String>> = emptyList(),
    ): Result {
        val n = name.trim()
        val meta = runCatching { PotMeta(base, categories).toJson() }.getOrNull() ?: return Result(Outcome.INVALID)
        if (n.isEmpty() || people.any { RevisionFormat.codePoints(it.name) !in 1..100 }) return Result(Outcome.INVALID)
        if (people.mapNotNull { it.userId }.let { it.size != it.toSet().size }) return Result(Outcome.INVALID)
        val suite = tenant.account.suite
        val created = when (
            val r = NodeCreate.create(
                tenant, scopeId, parentId, PotSession.KIND, n, tokenInput(n),
                sealMeta = { key, nodeId -> NodeMetaWrite.seal(suite, scopeId, nodeId, key, JsonObject(mapOf("v" to JsonPrimitive(1), PotMeta.KEY to meta))) },
            )
        ) {
            is NodeCreate.Result.Done -> r.created
            is NodeCreate.Result.Refused -> return Result(
                when (r.reason) {
                    NodeCreate.Reason.NAME_TAKEN -> Outcome.NAME_TAKEN
                    NodeCreate.Reason.BUSY -> Outcome.BUSY
                    NodeCreate.Reason.BLOCKED -> Outcome.BLOCKED
                    NodeCreate.Reason.REFUSED -> Outcome.REFUSED
                },
            )
        }
        if (people.isEmpty()) return Result(Outcome.CREATED, created.nodeId)
        val added = runCatching {
            tenant.forgetTree()
            val pot = PotSession.open(tenant, scopeId, created.nodeId) ?: return@runCatching false
            val now = PotSession.nowMs()
            val writes = people.mapIndexed { i, p -> PotWrite(pot.newId(), ParticipantBody(p.name, true, now + i, p.userId)) }
            writes.chunked(BATCH).all { pot.write(it) == PotSession.WriteOutcome.WRITTEN }
        }.getOrDefault(false)
        return Result(if (added) Outcome.CREATED else Outcome.PEOPLE_NOT_ADDED, created.nodeId)
    }
}
