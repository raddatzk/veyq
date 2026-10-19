package me.raddatz.veyq.core.account.identitypins

import me.raddatz.veyq.core.account.login.SessionService
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.answer
import me.raddatz.veyq.core.testing.assertWinnerAndLoser
import me.raddatz.veyq.core.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.configuration.MemorySize
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Slice tests for the operator-blind identity-pin store (en.account.identity-pinning, ADR 0028, ADR 0104): a sealed
 * head plus one sealed entry per pin, written together in one compare-and-set on the head's revision. A born-empty
 * account reads `{ revision: 0 }` and no entries; a write round-trips and bumps the revision; a stale revision is a
 * `409 stale_revision` and nothing of the write lands; the listing pages by `pinId`; and everything is keyed off the
 * caller's own token. Each test seeds a UUID-unique account so the JVM-lived H2 database cannot bleed rows between
 * tests. The race and the 25,000-entry write are why this class also runs on MariaDB
 * (`scripts/run_backend_races_on_mariadb.py`).
 */
@QuarkusTest
class IdentityPinsSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Test
    fun bornEmptyStoreReadsNullBlobRevisionZero() {
        val token = issueToken(seedUser())
        get(token)
            .then().statusCode(200)
            .body("blob", nullValue())
            .body("revision", equalTo(0))
        list(token).then().statusCode(200).body("entries.size()", equalTo(0)).body("more", equalTo(false))
    }

    @Test
    fun firstWriteRoundTripsAndBumpsToRevisionOne() {
        val token = issueToken(seedUser())
        write(token, 0, HEAD_1, put = listOf(pin(1)))
            .then().statusCode(200)
            .body("blob", equalTo(HEAD_1))
            .body("revision", equalTo(1))
        get(token).then().statusCode(200).body("blob", equalTo(HEAD_1)).body("revision", equalTo(1))
        assertEquals(listOf(pin(1)), entries(token))
    }

    @Test
    fun subsequentWriteAtCurrentRevisionBumpsAgain() {
        val token = issueToken(seedUser())
        write(token, 0, HEAD_1, put = listOf(pin(1))).then().statusCode(200).body("revision", equalTo(1))
        write(token, 1, HEAD_2, put = listOf(pin(2)))
            .then().statusCode(200)
            .body("blob", equalTo(HEAD_2))
            .body("revision", equalTo(2))
        assertEquals(listOf(pin(1), pin(2)), entries(token), "a write without replaceAll keeps what it does not name")
    }

    @Test
    fun staleRevisionOnFirstWriteIsConflict() {
        // A born-empty store only accepts a first write against revision 0 — a non-zero expected revision is stale,
        // and the entries it carried are not stored either.
        val token = issueToken(seedUser())
        write(token, 5, HEAD_1, put = listOf(pin(1)))
            .then().statusCode(409).body("code", equalTo("stale_revision"))
        get(token).then().statusCode(200).body("blob", nullValue()).body("revision", equalTo(0))
        assertEquals(emptyList<PinEntry>(), entries(token))
    }

    @Test
    fun staleRevisionAfterAWriteIsConflictAndNothingOfItLands() {
        // ADR 0104 decision 4: entries and head are ONE write. The stale write carries a put, a delete and a head;
        // none of the three may land, or a device would read a set its head does not describe.
        val token = issueToken(seedUser())
        write(token, 0, HEAD_1, put = listOf(pin(1))).then().statusCode(200).body("revision", equalTo(1))
        write(token, 0, HEAD_2, put = listOf(pin(2)), delete = listOf(pin(1).pinId))
            .then().statusCode(409).body("code", equalTo("stale_revision"))
        get(token).then().statusCode(200).body("blob", equalTo(HEAD_1)).body("revision", equalTo(1))
        assertEquals(listOf(pin(1)), entries(token), "neither the put nor the delete landed")
        // Control: the same write at the current revision lands whole.
        write(token, 1, HEAD_2, put = listOf(pin(2)), delete = listOf(pin(1).pinId)).then().statusCode(200)
        assertEquals(listOf(pin(2)), entries(token))
    }

    @Test
    fun storeIsPerAccountNotShared() {
        val mine = issueToken(seedUser())
        write(mine, 0, HEAD_1, put = listOf(pin(1))).then().statusCode(200)
        // A different account that has never written is still born-empty: neither the head nor an entry leaks.
        val theirs = issueToken(seedUser())
        get(theirs).then().statusCode(200).body("blob", nullValue()).body("revision", equalTo(0))
        assertEquals(emptyList<PinEntry>(), entries(theirs))
        // And its writes address its own rows only: a replaceAll and a delete of my pinId leave mine as they were.
        write(theirs, 0, HEAD_2, put = listOf(pin(2)), replaceAll = true).then().statusCode(200)
        write(theirs, 1, HEAD_2, delete = listOf(pin(1).pinId)).then().statusCode(200)
        assertEquals(listOf(pin(1)), entries(mine))
        assertEquals(listOf(pin(2)), entries(theirs))
    }

    @Test
    fun aWriteWithoutItsRevisionIs400AndStoresNothing() {
        // A missing `revision` must not bind to 0 — on a born-empty store 0 is exactly the revision that wins, so an
        // old client would write unchecked.
        val token = issueToken(seedUser())
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON)
            .body("""{"head":"$HEAD_1","put":[],"remove":[],"replaceAll":false}""")
            .`when`().put(PATH)
            .then().statusCode(400)
        get(token).then().statusCode(200).body("blob", nullValue()).body("revision", equalTo(0))
    }

    @Test
    fun missingTokenIsGeneric401() {
        given().`when`().get(PATH).then().statusCode(401).body("code", equalTo("authentication_failed"))
        given().`when`().get(ENTRIES).then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun aPutReplacesAnEntryAndADeleteRemovesOne() {
        val token = issueToken(seedUser())
        write(token, 0, HEAD_1, put = listOf(pin(1), pin(2))).then().statusCode(200)
        val replaced = PinEntry(pin(1).pinId, entryOf(101))
        write(token, 1, HEAD_2, put = listOf(replaced), delete = listOf(pin(2).pinId)).then().statusCode(200)
        assertEquals(listOf(replaced), entries(token))
        // A delete of an id that is not stored is ignored, not refused.
        write(token, 2, HEAD_1, delete = listOf(pin(9).pinId)).then().statusCode(200).body("revision", equalTo(3))
        assertEquals(listOf(replaced), entries(token))
    }

    @Test
    fun replaceAllRemovesEveryEntryItDoesNotPut() {
        // The reset (an empty set) and the rotation (every entry under a new id) write the whole set at once.
        val token = issueToken(seedUser())
        write(token, 0, HEAD_1, put = listOf(pin(1), pin(2), pin(3))).then().statusCode(200)
        write(token, 1, HEAD_2, put = listOf(pin(4)), replaceAll = true).then().statusCode(200)
        assertEquals(listOf(pin(4)), entries(token))
        write(token, 2, HEAD_1, replaceAll = true).then().statusCode(200).body("revision", equalTo(3))
        assertEquals(emptyList<PinEntry>(), entries(token), "an empty replaceAll leaves no entry")
    }

    @Test
    fun theListingPagesInPinIdOrderByKeyset() {
        val token = issueToken(seedUser())
        write(token, 0, HEAD_1, put = listOf(pin(5), pin(3), pin(1), pin(4), pin(2))).then().statusCode(200)
        val first = list(token, limit = 2).then().statusCode(200).extract()
        assertEquals(listOf(pin(1).pinId, pin(2).pinId), first.path<List<String>>("entries.pinId"))
        assertEquals(true, first.path<Boolean>("more"))
        val second = list(token, after = pin(2).pinId, limit = 2).then().statusCode(200).extract()
        assertEquals(listOf(pin(3).pinId, pin(4).pinId), second.path<List<String>>("entries.pinId"))
        assertEquals(true, second.path<Boolean>("more"))
        val last = list(token, after = pin(4).pinId, limit = 2).then().statusCode(200).extract()
        assertEquals(listOf(pin(5).pinId), last.path<List<String>>("entries.pinId"))
        assertEquals(false, last.path<Boolean>("more"), "a page that ends the set says so")
        // A page exactly as long as what is left also ends it — the one extra row asked for is what tells.
        val exact = list(token, after = pin(3).pinId, limit = 2).then().statusCode(200).extract()
        assertEquals(listOf(pin(4).pinId, pin(5).pinId), exact.path<List<String>>("entries.pinId"))
        assertEquals(false, exact.path<Boolean>("more"))
        // An absent `after` (or an empty one) is from the first; an absent limit is the whole page of 1000.
        assertEquals((1..5).map { pin(it).pinId }, list(token, after = "").then().extract().path<List<String>>("entries.pinId"))
    }

    @Test
    fun aListingAfterThatIsNoPinIdOrALimitOutOfRangeIs400() {
        val token = issueToken(seedUser())
        for (after in listOf("A".repeat(64), pin(1).pinId.dropLast(1), pin(1).pinId + "0", "zz")) {
            list(token, after = after).then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        for (limit in listOf(0, 1001, -1)) {
            list(token, limit = limit).then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        // Control: the bounds themselves are accepted.
        list(token, after = pin(1).pinId, limit = 1).then().statusCode(200)
        list(token, limit = 1000).then().statusCode(200)
    }

    @Test
    fun aContradictoryWriteIsRefusedAndStoresNothing() {
        val token = issueToken(seedUser())
        // The schema's own rule (`IdentityPinsWrite`'s `if`/`then`) is the shape's 400; the rules across the lists no
        // schema states are 422 `pins_contradictory` (inbox #507).
        val contradictions = listOf(
            Triple("one pinId put twice", Triple(listOf(pin(1), PinEntry(pin(1).pinId, entryOf(7))), emptyList<String>(), false), 422),
            Triple("one pinId deleted twice", Triple(emptyList(), listOf(pin(1).pinId, pin(1).pinId), false), 422),
            Triple("one pinId put and deleted", Triple(listOf(pin(1)), listOf(pin(1).pinId), false), 422),
            Triple("a delete beside replaceAll", Triple(listOf(pin(1)), listOf(pin(2).pinId), true), 400),
        )
        for ((name, w, status) in contradictions) {
            write(token, 0, HEAD_1, put = w.first, delete = w.second, replaceAll = w.third)
                .then().statusCode(status).body("code", equalTo(if (status == 400) "invalid_request" else "pins_contradictory"))
            get(token).then().body("revision", equalTo(0))
            assertEquals(emptyList<PinEntry>(), entries(token), name)
        }
        // Control: the same request without the contradiction lands.
        write(token, 0, HEAD_1, put = listOf(pin(1)), delete = listOf(pin(2).pinId)).then().statusCode(200)
    }

    @Test
    fun twoRacingFirstWritesAnswerOneWrittenAndOneStale() {
        // The single-blob store lost a racing first insert to its primary key — a 500. Locking the account row first
        // decides the head's existence under the lock: one write lands at revision 1, the other is stale.
        repeat(RACE_ROUNDS) { round ->
            val token = issueToken(seedUser())
            val answers = race(
                { write(token, 0, HEAD_1, put = listOf(pin(1))).then().extract().answer() },
                { write(token, 0, HEAD_2, put = listOf(pin(2))).then().extract().answer() },
            )
            assertWinnerAndLoser(200, 409 to "stale_revision", answers, "round $round")
            assertEquals(1, entries(token).size, "round $round: only the winner's entry is stored")
            get(token).then().body("revision", equalTo(1))
        }
    }

    @Test
    fun aWriteOf25000EntriesLandsAndTheStoreHoldsNoMore() {
        // ADR 0104 decision 6: the one write that carries every entry (a rotation, a reset) is bounded by the
        // contract's 25,000, which must fit the server's request body limit with the rotation's other fields beside it.
        val user = seedUser()
        val token = issueToken(user)
        val all = (1..25_000).map { pin(it) }
        val body = writeBody(0, HEAD_1, all, emptyList(), false)
        val limit = ConfigProvider.getConfig().getValue("quarkus.http.limits.max-body-size", MemorySize::class.java)
        println("identity-pins: a write of 25,000 entries is ${body.length} bytes; the body limit is ${limit.asLongValue()}")
        assertTrue(body.length + ROTATION_OVERHEAD < limit.asLongValue(), "the rotation's whole body fits the limit")
        send(token, body).then().statusCode(200).body("revision", equalTo(1))
        assertEquals(25_000L, count(user))
        // And it reads back whole, page by page, in pinId order — what a device lists before it checks the head.
        val listed = entries(token)
        assertEquals(25_000, listed.size)
        assertEquals(listOf(pin(1), pin(445), pin(1000), pin(25_000)), listOf(listed[0], listed[444], listed[999], listed[24_999]))

        // The store holds no more: one more entry is refused, and nothing of that write lands — not even its head. An
        // answer about what the store holds, not about the request's shape (inbox #507).
        write(token, 1, HEAD_2, put = listOf(pin(25_001)))
            .then().statusCode(422).body("code", equalTo("pin_store_full"))
        get(token).then().body("blob", equalTo(HEAD_1)).body("revision", equalTo(1))
        assertEquals(25_000L, count(user))
        // Control: a replacement inside the bound lands.
        write(token, 1, HEAD_2, put = listOf(pin(25_001)), delete = listOf(pin(1).pinId)).then().statusCode(200)
        assertEquals(25_000L, count(user))

        // 25,001 in one request is the contract's refusal, before any handler.
        send(token, writeBody(2, HEAD_1, all + pin(25_002), emptyList(), true))
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        get(token).then().body("revision", equalTo(2))
    }

    // --- helpers ---

    private fun issueToken(userId: String): String = sessions.issue(userId).accessToken

    private fun get(token: String) =
        given().header("Authorization", "Bearer $token").`when`().get(PATH)

    private fun list(token: String, after: String? = null, limit: Int? = null) =
        given().header("Authorization", "Bearer $token")
            .apply { after?.let { queryParam("after", it) }; limit?.let { queryParam("limit", it) } }
            .`when`().get(ENTRIES)

    /** Every entry of the caller's store, read page by page as a device does. */
    private fun entries(token: String): List<PinEntry> {
        val out = mutableListOf<PinEntry>()
        var after: String? = null
        do {
            val page = list(token, after).then().statusCode(200).extract()
            val ids = page.path<List<String>>("entries.pinId")
            val sealed = page.path<List<String>>("entries.entry")
            out += ids.zip(sealed) { id, e -> PinEntry(id, e) }
            after = ids.lastOrNull()
        } while (page.path<Boolean>("more"))
        return out
    }

    private fun count(userId: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        IdentityPinEntryEntity.count("accountId", userId)
    }

    private fun write(
        token: String,
        revision: Long,
        head: String,
        put: List<PinEntry> = emptyList(),
        delete: List<String> = emptyList(),
        replaceAll: Boolean = false,
    ) = send(token, writeBody(revision, head, put, delete, replaceAll))

    private fun send(token: String, body: String) =
        given().header("Authorization", "Bearer $token").contentType(ContentType.JSON).body(body).`when`().put(PATH)

    private fun writeBody(revision: Long, head: String, put: List<PinEntry>, delete: List<String>, replaceAll: Boolean) =
        buildString {
            append("""{"revision":$revision,"head":"$head","put":[""")
            put.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("""{"pinId":"${e.pinId}","entry":"${e.entry}"}""")
            }
            append("""],"remove":[""")
            append(delete.joinToString(",") { "\"$it\"" })
            append("""],"replaceAll":$replaceAll}""")
        }

    companion object {
        private const val PATH = "/api/account/identity-pins"
        private const val ENTRIES = "/api/account/identity-pins/entries"
        private const val HEAD_1 = "c2VhbGVkLWhlYWQtb25l"
        private const val HEAD_2 = "c2VhbGVkLWhlYWQtdHdv"

        /** What a rotation request carries besides its pins: a new identity bundle and its wrapped private key, two
         *  rewritten cold wraps, a signature and the device register (each field's contract cap, summed generously). */
        private const val ROTATION_OVERHEAD = 8_192 + 8_192 + 4 * 1_024 + 128 + 65_532 + 1_024

        /** The `n`-th pin: its id 64 lowercase hex characters, its entry the 296 base64 characters every real one
         *  seals to (a 192-byte padded plaintext + 29 bytes of seal). */
        fun pin(n: Int) = PinEntry("%064x".format(n), entryOf(n))

        fun entryOf(n: Int): String = Base64.getEncoder().encodeToString(ByteArray(221) { (it + n).toByte() })
    }
}
