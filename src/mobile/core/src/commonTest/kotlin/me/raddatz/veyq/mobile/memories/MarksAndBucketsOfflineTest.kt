package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.session.Reach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The marks and the buckets are kept reads (ADR 0149 decisions 4 and 6, ADR 0140 decision 5): a Memory read online shows its
 * badges and names its guest offline, from the store; nothing is marked offline (decision 7).
 */
class MarksAndBucketsOfflineTest {
    @Test
    fun aKeptMemoryShowsItsBadgesAndNamesItsGuestOffline_noToggle() = runTest {
        val t = PlayedTenant()
        val s = t.scope("s1", kind = "GROUP")
        val m = t.node(s, "memory", name = "Lisbon")
        val photo = t.medium(m, "p").id
        val guest = t.id("guest")
        val share = "A".repeat(43)
        var stored: me.raddatz.veyq.mobile.api.memories.models.PutItemFavoritesRequest? = null
        t.route = { r ->
            val path = r.url.encodedPath
            when {
                path.endsWith("/item-favorites") && r.method.value == "GET" -> 200 to (stored?.let { """[{"self":true,"ciphertext":"${it.ciphertext}","wrappedValueKey":"${it.wrappedValueKey}"}]""" } ?: "[]")
                path.endsWith("/item-favorites") -> {
                    val body = (r.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    stored = kotlinx.serialization.json.Json.decodeFromString(me.raddatz.veyq.mobile.api.memories.models.PutItemFavoritesRequest.serializer(), body)
                    204 to ""
                }
                path.endsWith("/buckets") -> 200 to """{"buckets":[{"bucketId":"${t.id("bucket")}","ownerKey":"share:$guest","isPrimary":true,"mediaCount":1,"shareId":"$share","sealedName":"${
                    b64(t.suite.sealField(Context("share-identity-name", "scopeId" to s.id, "shareId" to share, "identityId" to guest), m.key, "Ben".encodeToByteArray()))
                }"}]}"""
                else -> null
            }
        }
        t.remember()
        val online = t.resume()
        val tenant = online.open(online.tenants.first())
        // the Memory viewed online: its tree and its listing kept, what its marks and buckets hang from
        MemoryMedia.list(tenant, s.id, m.id)
        val marks = ItemMarks.of(tenant, s.id, m.id)
        marks.load()
        assertEquals(ItemMarks.Toggled.DONE, marks.toggle(photo))
        ItemMarks.of(tenant, s.id, m.id).load() // the listing as the server answers it now, kept
        assertEquals(mapOf("share:$guest" to "Ben"), Buckets.guestNames(tenant, s.id, m.id))
        online.retire()

        t.reachable = false
        val offline = t.resume()
        assertIs<Reach.Offline>(offline.reach.value)
        val shown = offline.open(offline.tenants.first())
        MemoryMedia.list(shown, s.id, m.id)
        val kept = ItemMarks.of(shown, s.id, m.id)
        assertEquals(true, kept.load(), "the marks read from the store")
        assertEquals(1 to true, kept.count(photo) to kept.markedByMe(photo))
        assertEquals(mapOf("share:$guest" to "Ben"), Buckets.guestNames(shown, s.id, m.id), "the guest named from the kept buckets")
        assertEquals(ItemMarks.Toggled.SAVE_FAILED, kept.toggle(photo), "nothing is marked offline")
        assertEquals(true, kept.markedByMe(photo), "the heart back as it was")
        offline.retire()
    }
}
