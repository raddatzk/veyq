package me.raddatz.veyq.mobile.offline

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Queued`, the sender and the outcomes over core's node writes (ADR 0153 decisions 3 and 4, the CR's phase 2), each over
 * a server the test plays with real seals: sent; `stale_epoch` re-sealed and sent under the node's new key; a rename
 * meeting `stale_placement` held, never rebuilt; `404` refused; a transport failure leaves the write waiting; `id_taken`
 * with the folder there sent, without it held; a held write holds what waits for it; a write made offline sent at the
 * reconnect. And the closed list derived: every value's sender run, its operations read off the wire.
 *
 * Bites (each one edit, seen red, then restored): `NodeCreate` sealing its request once and re-sending it after
 * `stale_epoch` → [stale_epoch_is_resealed_under_the_new_key_and_sent] red (the body opens only under the old key); the
 * queued rename taking `NodePlacement`'s rebuild (`placementSeen == null` dropped from its retry) →
 * [a_rename_meeting_stale_placement_is_held_not_rebuilt] red; the trash sender also purging (a never-queue operation) →
 * [every_queued_operation_is_derived_from_its_sender_and_none_is_in_the_never_column] red.
 */
class QueuedTest {
    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private class Setup(val t: PlayedTenant, val a: VeyqAccount, val scope: PlayedTenant.Scope, val folder: PlayedTenant.Node)

    /** A personal space with one folder at its top ("Old"), remembered on this device and resumed online. */
    private suspend fun setup(): Setup {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val f = t.node(s, "f1", kind = "FOLDER", name = "Old")
        t.remember()
        return Setup(t, t.resume(), s, f)
    }

    private fun HttpRequestData.body(): String = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private fun isCreate(r: HttpRequestData) = r.method == HttpMethod.Post && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes"))

    private fun isUpdate(r: HttpRequestData) = r.method == HttpMethod.Patch && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes/[^/]+"))

    private fun isDelete(r: HttpRequestData) = r.method == HttpMethod.Delete && r.url.encodedPath.matches(Regex("/api/scopes/[^/]+/nodes/[^/]+"))

    private suspend fun Setup.writes() = assertNotNull(a.outbox).writes()

    @Test
    fun a_folder_a_rename_and_a_trash_are_sent_and_leave_the_outbox() = runTest {
        val x = setup()
        val other = x.t.node(x.scope, "f2", kind = "FOLDER", name = "Doomed")
        val sent = mutableListOf<String>()
        x.t.route = { r ->
            when {
                isCreate(r) -> { sent += "create"; 201 to """{"nodeId":"${Json.parseToJsonElement(r.body()).jsonObject["nodeId"]!!.jsonPrimitive.content}"}""" }
                isUpdate(r) -> { sent += "rename"; 204 to "" }
                isDelete(r) -> { sent += "trash"; 204 to "" }
                else -> null
            }
        }
        assertTrue(x.a.queues(Queued.FOLDER_CREATE))
        assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, null, "  Trip  ", 1))
        assertTrue(QueuedNodes.rename(x.a, "t1", x.scope.id, x.folder.id, 0, "New", 2))
        assertTrue(QueuedNodes.trash(x.a, "t1", x.scope.id, other.id, 3))
        val report = assertNotNull(x.a.sendQueued())
        assertEquals(OutboxSender.Report(3, 0, 0, 0), report)
        assertEquals(listOf("create", "rename", "trash"), sent, "not sent oldest first")
        assertEquals(emptyList(), x.writes())
        x.a.retire()
    }

    @Test
    fun stale_epoch_is_resealed_under_the_new_key_and_sent() = runTest {
        val x = setup()
        val oldKey = x.folder.key.copyOf()
        val bodies = mutableListOf<JsonObject>()
        x.t.route = { r ->
            if (!isCreate(r)) null else {
                bodies += Json.parseToJsonElement(r.body()).jsonObject
                if (bodies.size == 1) {
                    // the parent re-keyed by another device between the read and the write
                    x.t.rekey(x.folder)
                    409 to """{"code":"stale_epoch"}"""
                } else 201 to """{"nodeId":"${bodies.last()["nodeId"]!!.jsonPrimitive.content}"}"""
            }
        }
        val w = assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, x.folder.id, "Inside", 1))
        assertEquals(OutboxSender.Report(1, 0, 0, 0), x.a.sendQueued())
        assertEquals(2, bodies.size)
        val accepted = bodies.last()
        assertEquals(w.id, accepted["nodeId"]!!.jsonPrimitive.content, "the queued id was not the one created")
        val binding = Context("node-name", "scopeId" to x.scope.id, "nodeId" to w.id, "epoch" to 0L, "placementRevision" to 0L)
        val sealed = unb64(accepted["encryptedName"]!!.jsonPrimitive.content)
        assertEquals("Inside", x.t.suite.openField(binding, x.folder.key, sealed).decodeToString(), "the body sent does not open under the new key")
        assertFailsWith<Exception>("the body sent still opens under the old key") { x.t.suite.openField(binding, oldKey, sealed) }
        x.a.retire()
    }

    @Test
    fun a_rename_meeting_stale_placement_is_held_not_rebuilt() = runTest {
        val x = setup()
        var updates = 0
        x.t.route = { r -> if (isUpdate(r)) { updates++; if (updates == 1) 409 to """{"code":"stale_placement"}""" else 204 to "" } else null }
        assertTrue(QueuedNodes.rename(x.a, "t1", x.scope.id, x.folder.id, 0, "Mine", 1))
        assertEquals(OutboxSender.Report(0, 1, 0, 0), x.a.sendQueued())
        assertEquals(1, updates, "the rename was built again on the newer placement")
        val held = x.writes().single()
        assertEquals(QueuedWrite.State.HELD, held.state)
        assertEquals("stale_placement", held.reason)
        // a held write is the person's: a later run leaves it
        assertEquals(OutboxSender.Report(0, 0, 0, 0), x.a.sendQueued())
        assertEquals(1, updates)
        x.a.retire()
    }

    @Test
    fun a_rename_of_a_node_placed_since_it_was_seen_is_held_without_a_request() = runTest {
        val x = setup()
        var updates = 0
        x.t.route = { r -> if (isUpdate(r)) { updates++; 204 to "" } else null }
        // the person saw revision 3; the tree now serves another
        assertTrue(QueuedNodes.rename(x.a, "t1", x.scope.id, x.folder.id, 3, "Mine", 1))
        x.a.sendQueued()
        assertEquals(0, updates)
        assertEquals(QueuedWrite.State.HELD, x.writes().single().state)
        x.a.retire()
    }

    @Test
    fun a_target_gone_is_refused() = runTest {
        val x = setup()
        x.t.route = { r -> if (isDelete(r)) 404 to """{"code":"node_not_found"}""" else null }
        assertTrue(QueuedNodes.trash(x.a, "t1", x.scope.id, x.folder.id, 1))
        // a rename of a node no longer in the tree
        assertTrue(QueuedNodes.rename(x.a, "t1", x.scope.id, x.t.id("gone"), 0, "Nobody", 2))
        assertEquals(OutboxSender.Report(0, 0, 2, 0), x.a.sendQueued())
        assertEquals(listOf("not_found", "not_found"), x.writes().map { it.reason })
        assertTrue(x.writes().all { it.state == QueuedWrite.State.REFUSED })
        x.a.retire()
    }

    @Test
    fun a_transport_failure_leaves_the_write_waiting() = runTest {
        val x = setup()
        x.t.route = { r -> if (isCreate(r)) throw IllegalStateException("the network went") else null }
        assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, null, "Trip", 1))
        assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, null, "Second", 2))
        val report = assertNotNull(x.a.sendQueued())
        assertEquals(OutboxSender.Report(0, 0, 0, 2), report)
        assertIs<Reach.Offline>(x.a.reach.value)
        assertTrue(x.writes().all { it.state == QueuedWrite.State.WAITING && it.reason == null })
        x.a.retire()
    }

    @Test
    fun id_taken_with_the_folder_there_is_sent_and_without_it_held() = runTest {
        val x = setup()
        val there = x.t.id("there")
        x.t.route = { r ->
            if (!isCreate(r)) null else {
                val id = Json.parseToJsonElement(r.body()).jsonObject["nodeId"]!!.jsonPrimitive.content
                // the first send of "there" reached the server, its answer was lost: the folder stands as the person made it
                if (id == there && x.scope.nodes.none { it.id == there }) x.t.node(x.scope, "there", kind = "FOLDER", name = "Trip")
                409 to """{"code":"id_taken"}"""
            }
        }
        val box = assertNotNull(x.a.outbox)
        val intent = QueuedNodes.encode(QueuedNodes.FolderIntent.serializer(), QueuedNodes.FolderIntent(null, "Trip"))
        assertTrue(box.put(QueuedWrite(there, Queued.FOLDER_CREATE.name, "t1", x.scope.id, node = there, intent = intent, at = 1)))
        assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, null, "Someone else's", 2))
        assertEquals(OutboxSender.Report(1, 1, 0, 0), x.a.sendQueued())
        val held = x.writes().single()
        assertEquals("id_taken", held.reason)
        assertEquals(QueuedWrite.State.HELD, held.state)
        x.a.retire()
    }

    @Test
    fun a_held_write_holds_what_waits_for_it_and_nothing_else() = runTest {
        val x = setup()
        val created = mutableListOf<String>()
        x.t.route = { r ->
            if (!isCreate(r)) null else {
                val body = Json.parseToJsonElement(r.body()).jsonObject
                created += body["nodeId"]!!.jsonPrimitive.content
                409 to """{"code":"slug_taken"}"""
            }
        }
        val parent = assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, null, "Taken", 1))
        val child = assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, parent.id, "Inside", 2))
        assertEquals(parent.id, child.after)
        assertTrue(QueuedNodes.rename(x.a, "t1", x.scope.id, x.folder.id, 0, "Unrelated", 3))
        x.t.route = x.t.route.let { inner -> { r -> if (isUpdate(r)) 204 to "" else inner!!(r) } }
        assertEquals(OutboxSender.Report(1, 1, 0, 1), x.a.sendQueued())
        assertEquals(listOf(parent.id), created, "a write waiting for a held one was sent")
        assertEquals(listOf(QueuedWrite.State.HELD, QueuedWrite.State.WAITING), x.writes().map { it.state })
        x.a.retire()
    }

    @Test
    fun a_rename_or_trash_of_a_folder_made_offline_changes_its_queued_create() = runTest {
        val x = setup()
        val made = assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, null, "Draft", 1))
        val inside = assertNotNull(QueuedNodes.folder(x.a, "t1", x.scope.id, made.id, "Inside", 2))
        assertTrue(QueuedNodes.rename(x.a, "t1", x.scope.id, made.id, 0, "Final", 3))
        assertEquals(listOf(made.id, inside.id), x.writes().map { it.id })
        assertEquals(JsonPrimitive("Final"), x.writes().first().intent["name"])
        assertTrue(QueuedNodes.trash(x.a, "t1", x.scope.id, made.id, 4))
        assertEquals(emptyList(), x.writes(), "the trash of a folder made offline left it or what waited for it")
        x.a.retire()
    }

    @Test
    fun a_write_made_offline_is_sent_at_the_reconnect() = runTest {
        val x = setup()
        x.a.retire()
        x.t.reachable = false
        val offline = x.t.resume()
        assertIs<Reach.Offline>(offline.reach.value)
        assertTrue(offline.queues(Queued.FOLDER_CREATE))
        val w = assertNotNull(QueuedNodes.folder(offline, "t1", x.scope.id, null, "Made on the train", 1))
        val created = mutableListOf<String>()
        x.t.route = { r -> if (isCreate(r)) { created += Json.parseToJsonElement(r.body()).jsonObject["nodeId"]!!.jsonPrimitive.content; 201 to """{"nodeId":"${created.last()}"}""" } else null }
        x.t.reachable = true
        assertTrue(offline.reconnect())
        offline.awaitSending()
        assertEquals(listOf(w.id), created)
        assertEquals(0, assertNotNull(offline.outbox).count())
        offline.retire()
    }

    // ---- the closed list, derived

    /** The operations of ADR 0153 decision 3's never-queue column that a node write could reach. */
    private val never = setOf("restoreNode", "purgeNode", "moveNode", "rekeyNode", "createShareLink", "putScopeGrant")

    /** Each write request's operation, read off its method and path; anything this table does not know is named as unknown. */
    private fun operationOf(r: HttpRequestData): String {
        val p = r.url.encodedPath
        return when {
            isCreate(r) -> "createNode"
            isUpdate(r) -> "updateNode"
            isDelete(r) -> "deleteNode"
            r.method == HttpMethod.Post && p.matches(Regex("/api/scopes/[^/]+/nodes/[^/]+/restore")) -> "restoreNode"
            r.method == HttpMethod.Post && p.matches(Regex("/api/scopes/[^/]+/nodes/[^/]+/purge")) -> "purgeNode"
            else -> "unknown ${r.method.value} $p"
        }
    }

    private fun sample(q: Queued, x: Setup): QueuedWrite = when (q) {
        Queued.FOLDER_CREATE -> QueuedWrite(x.t.id("sample"), q.name, "t1", x.scope.id, node = x.t.id("sample"), intent = QueuedNodes.encode(QueuedNodes.FolderIntent.serializer(), QueuedNodes.FolderIntent(x.folder.id, "Sample")), at = 0)
        Queued.NODE_RENAME -> QueuedWrite("r", q.name, "t1", x.scope.id, node = x.folder.id, intent = QueuedNodes.encode(QueuedNodes.RenameIntent.serializer(), QueuedNodes.RenameIntent("Renamed", 0)), at = 0)
        Queued.NODE_TRASH -> QueuedWrite("t", q.name, "t1", x.scope.id, node = x.folder.id, at = 0)
    }

    @Test
    fun every_queued_operation_is_derived_from_its_sender_and_none_is_in_the_never_column() = runTest {
        val x = setup()
        val tenant: TenantSession = x.a.open(x.a.tenants.first())
        for (q in Queued.entries) {
            assertTrue(q.operations.none { it in never }, "$q declares a never-queue operation")
            val calls = mutableListOf<Pair<String, String>>()
            // every write answered as refused: what matters is what was asked
            x.t.route = { r -> if (r.method != HttpMethod.Get) { calls += operationOf(r) to r.body(); 404 to """{"code":"node_not_found"}""" } else null }
            tenant.forgetTree()
            AppKinds.queuedSender(q).send(tenant, sample(q, x))
            val ops = calls.map { it.first }.toSet()
            assertTrue(ops.isNotEmpty(), "$q's sender sent nothing")
            assertTrue(ops.none { it in never || it.startsWith("unknown") }, "$q's sender called $ops")
            assertEquals(q.operations, ops, "$q declares ${q.operations}, its sender called $ops")
            // a rename is a placement under the parent the node is under: never a move
            if (q == Queued.NODE_RENAME) for ((_, body) in calls) {
                val parent = Json.parseToJsonElement(body).jsonObject["parentId"]
                assertTrue(parent == null || parent == JsonNull, "the queued rename moved the node to $parent")
            }
        }
        x.a.retire()
    }
}
