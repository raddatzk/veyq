package me.raddatz.veyq.mobile.composition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.raddatz.veyq.mobile.depot.FolderUpload
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What the Apple core UI asks of the platform instead of an application's object ([ApplePlatform], inbox #1066): a state
 * watched from its current value on, a picked file's source handed back by its URL, a walk's entries as the core's —
 * a file's source only for a file, each kind the same — and the derived tiers' bytes as they were.
 *
 * Bites (each one edit, seen red, then restored): `found` mapping `LINK` to `OTHER` → the kinds' case red; `urlOf`
 * answering null → the source's case red.
 */
@OptIn(ExperimentalForeignApi::class)
class ApplePlatformTest {
    @Test
    fun aWatchHearsTheCurrentValueFirst() = runTest {
        val flow = MutableStateFlow(7)
        val heard = CompletableDeferred<Any?>()
        val watch = ApplePlatform.watch(flow) { if (!heard.isCompleted) heard.complete(it) }
        // the watch runs on core's threads: waited for in real time, not the test's virtual clock
        assertEquals(7, withContext(Dispatchers.Default) { withTimeout(5_000) { heard.await() } })
        watch.stop()
    }

    @Test
    fun aSourceIsHandedBackByItsUrl() {
        val url = NSURL.fileURLWithPath("/tmp/veyq-apple-platform-test.jpg")
        val source = ApplePlatform.source(url, "a.jpg", 3, "image/jpeg", null)
        assertEquals("a.jpg", source.name)
        assertEquals(url.path, ApplePlatform.urlOf(source)?.path)
    }

    @Test
    fun aWalkIsTheCoresWithAFileAloneReadable() {
        val root = NSURL.fileURLWithPath("/tmp/veyq-walk")
        val kinds = ApplePlatform.EntryKind.entries
        val found = ApplePlatform.found(root, kinds.map { ApplePlatform.Entry("d/${it.name}", "/tmp/veyq-walk/d/${it.name}", it, 1, "text/plain", null) })
        assertEquals(listOf(FolderUpload.Kind.FILE, FolderUpload.Kind.DIRECTORY, FolderUpload.Kind.LINK, FolderUpload.Kind.UNREADABLE, FolderUpload.Kind.OTHER), found.map { it.kind })
        assertEquals("d/FILE", found[0].path)
        assertNotNull(found[0].source)
        found.drop(1).forEach { assertNull(it.source) }
    }

    @Test
    fun theTiersKeepTheirBytes() {
        val none = ApplePlatform.derived(null, null, 4, null)
        assertNull(none.preview)
        assertEquals(4, none.width)
        assertSame(null, none.height)
        val bytes = byteArrayOf(1, 2, 3)
        val data = memScoped { NSData.create(bytes = allocArrayOf(bytes), length = bytes.size.toULong()) }
        assertContentEquals(bytes, ApplePlatform.derived(data, null, null, null).preview)
    }
}
