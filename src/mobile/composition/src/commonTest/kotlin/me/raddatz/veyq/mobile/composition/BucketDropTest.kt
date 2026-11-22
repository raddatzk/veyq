package me.raddatz.veyq.mobile.composition

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.memories.Buckets
import me.raddatz.veyq.mobile.memories.Contributions
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A drop onto the bucket a Memory's Contributions view shows (inbox #1065, [DropTarget.Bucket]): its photos and videos go
 * into that bucket — through the view's own upload, filed by a move into a sub-bucket — the rest said as a gallery's drop
 * says it; another uploader's root refuses it in its own words; nothing is handed back from a refusal. The apps used to
 * upload such a drop into the gallery (`DropTarget.Gallery`), not into the bucket shown.
 */
class BucketDropTest {
    private val me = "u-me"

    private class Page {
        val uploads = mutableListOf<Pair<List<String>, Boolean>>()
        val door = object : GalleryModel.Door {
            override suspend fun gallery() = MemoryMedia.Gallery(emptyList(), 1, 0)
            override suspend fun scopeNodes() = emptyList<NodeRef>()
            override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? = null
            override fun reader(item: MemoryMedia.Item) = null
            override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
            override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
            override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
            override fun upload(sources: List<UploadSource>) = sources.size.also { uploads += sources.map { it.name } to false }
            override fun upload(sources: List<UploadSource>, filed: suspend (mediaId: String) -> Unit) =
                sources.size.also { uploads += sources.map { it.name } to true }
            override val revisions = MutableStateFlow<Any?>(Any())
            override val uploaded = MutableSharedFlow<String>()
        }
    }

    private fun buckets(me: String) = object : Contributions.Door {
        override suspend fun list() = Buckets.Listed(
            listOf(
                Buckets.Bucket("p-other", "user:u-other", null, true, 0, null, null, null),
                Buckets.Bucket("p-me", "user:$me", null, true, 0, null, null, null),
                Buckets.Bucket("s-me", "user:$me", "p-me", false, 0, null, null, null),
            ),
            null,
        ) to mapOf("s-me" to "Sunsets")
        override val self = me
        override fun person(userId: String): String? = null
        override suspend fun create(name: String, parent: String?) = Buckets.Outcome.DONE
        override suspend fun rename(bucketId: String, name: String) = Buckets.Outcome.DONE
        override suspend fun delete(bucketId: String) = Buckets.Outcome.DONE
        override suspend fun move(mediaIds: List<String>, target: String?) = Buckets.Outcome.DONE
    }

    private suspend fun TestScope.open(page: Page): Contributions {
        val g = GalleryModel(NodeAt("s", "m", "MEMORY", "Ostsee"), page.door, backgroundScope, backgroundScope).also { runCurrent() }
        return Contributions(g, buckets(me)).also { it.read() }
    }

    private fun file(name: String, type: String?) = Dropped.File(UploadSource.of(name, 4, type, null) { _, n -> ByteArray(n) })

    @Test
    fun aDropOntoTheCallersSubBucketGoesIntoItTheRestSaid() = runTest {
        val page = Page()
        val c = open(page)
        c.pick("s-me")
        val left = mutableListOf<String>()
        val items = listOf(file("beach.jpg", "image/jpeg"), file("notes.pdf", "application/pdf"), Dropped.Folder("/x/Holiday", null, emptyList()))
        val outcome = assertIs<DropOutcome.Uploaded>(DropTarget.Bucket(c).drop(items) { left += it.name })
        assertEquals(1, outcome.queued)
        assertEquals(listOf(listOf("beach.jpg") to true), page.uploads, "into the bucket, filed — not the gallery's plain upload")
        assertEquals(listOf(Notice(Msg.MEMORY_DROP_FOLDER, "Holiday"), Notice(Msg.MEMORY_NOT_MEDIA, 1)), outcome.notices)
        assertEquals(listOf("notes.pdf"), left)
    }

    @Test
    fun aDropOntoAnotherUploadersRootIsRefusedInItsOwnWords() = runTest {
        val page = Page()
        val c = open(page)
        c.pick("p-other")
        val left = mutableListOf<String>()
        val outcome = assertIs<DropOutcome.Refused>(DropTarget.Bucket(c).drop(listOf(file("beach.jpg", "image/jpeg"))) { left += it.name })
        assertEquals(Notice(Msg.MEMORY_BUCKETS_DROP_NOT_OWN), outcome.notice)
        assertEquals(emptyList(), page.uploads)
        assertEquals(emptyList(), left, "a refusal keeps nothing and hands nothing back: the files are the caller's still")
    }
}
