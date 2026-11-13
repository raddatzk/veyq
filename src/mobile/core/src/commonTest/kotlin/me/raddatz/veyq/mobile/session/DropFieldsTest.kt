package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * An upload-only link's drop key (`ShareLinks.dropFields`, the web's `dropKeysForShare`; ADR 0031 point 7, the seals
 * `v1.json#drop_key` pins): a Memory's or a chapter's first WRITE_ONLY link makes the node's drop key and seals it under
 * the node's key, bound to the node; every later link reuses it — every upload so far is sealed to it — and seals only its
 * public half under its own `K_share`. Over the real suite.
 */
class DropFieldsTest {
    private val suite = V1(testPrimitives)
    private val nodeKey = ByteArray(32) { (it * 7).toByte() }
    private val scope = "0b6f2c1e-6a43-4a3e-9d1f-1c2b3a4d5e6f"
    private val node = "5f0e9c8b-7a6d-4e5f-8a1b-2c3d4e5f6a7b"
    private val other = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    private val private = Context("drop-private-key", "scopeId" to scope, "nodeId" to node)

    private fun publicUnder(kShare: ByteArray, sealed: String) = suite.open(Context("drop-public-key"), kShare, unb64(sealed))

    @Test
    fun theFirstLinkSetsTheNodesDropKeyAndTheSecondReusesIt() {
        val first = ByteArray(32) { 1 }
        val (public1, private1) = ShareLinks.dropFields(suite, scope, node, nodeKey, null, first)
        val sealed = assertNotNull(private1, "the first link sets the node's drop key")
        val seed = suite.deserializeDropKey(suite.open(private, nodeKey, unb64(sealed)))
        val public = suite.serializeHybridPublic(suite.dropKeyPublic(seed))
        assertContentEquals(public, publicUnder(first, public1), "its guests seal to that key's public half")

        val second = ByteArray(32) { 2 }
        val (public2, private2) = ShareLinks.dropFields(suite, scope, node, nodeKey, sealed, second)
        assertNull(private2, "a later link sets none: the node keeps its one drop key")
        assertContentEquals(public, publicUnder(second, public2), "and seals the same public half under its own K_share")
    }

    @Test
    fun aDropKeyIsBoundToItsNode() {
        val (_, sealed) = ShareLinks.dropFields(suite, scope, node, nodeKey, null, ByteArray(32) { 1 })
        assertFailsWith<Exception>("another node's drop key does not open as this one's") {
            ShareLinks.dropFields(suite, scope, other, nodeKey, sealed, ByteArray(32) { 2 })
        }
    }
}
