package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files by path (ADR 0137 decision 3) on every native target — macOS, Linux and Windows: [FileSink] writes a file of
 * uneven frames, [FileSource] reads it at offsets and whole, through a name with `ü`, `€` and a CJK character, and the
 * directory as the system lists it ([listDir], the platform's own listing, never the calls under test) holds that name
 * exactly. A sink that fails mid-way leaves nothing behind.
 *
 * A picked directory is walked by the platform's own listing ([PickedPaths.walk], ADR 0137's Consequences): a nested directory
 * under a non-ASCII name, a hidden file kept, a link reported and never followed, an entry that does not list said.
 *
 * The bites, each measured red: Windows' `openFile` over plain `fopen` (the name listed is another); [FileSink.fill]
 * without its removal (the partial file stays); `pathFacts` over `stat` instead of `lstat` (the link followed).
 */
class FilesTest {
    private lateinit var dir: String

    @BeforeTest
    fun setUp() {
        dir = "${tempDir()}${separator}veyq-files-${Random.nextLong().toULong()}"
        assertTrue(makeDir(dir), "cannot make $dir")
    }

    @AfterTest
    fun tearDown() {
        removeTree(dir)
    }

    private val name = "Büro € 中文.bin"

    private val frames: List<ByteArray> = listOf(1, 4_097, 65_539, 0, 7, 131_072, 3).mapIndexed { i, n -> Random(i).nextBytes(n) }
    private val whole: ByteArray = frames.fold(ByteArray(0)) { a, b -> a + b }

    @Test
    fun aFileOfUnevenFramesIsWrittenAndReadAtOffsetsUnderItsOwnName() = runTest {
        val path = "$dir$separator$name"
        val written = FileSink.write(path) { sink ->
            frames.forEach(sink)
            frames.size
        }
        assertEquals(frames.size, written)
        assertEquals(listOf(name), listDir(dir), "the directory lists the file under its own name")

        val source = FileSource(path, name, whole.size.toLong(), null, null)
        try {
            // backwards, in uneven pieces, each against the bytes written
            var end = whole.size
            var piece = 1
            while (end > 0) {
                val from = maxOf(0, end - piece)
                assertContentEquals(whole.copyOfRange(from, end), source.read(from.toLong(), end - from), "bytes $from..$end")
                end = from
                piece = piece * 3 + 1
            }
            // whole, and past its end only what there is
            assertContentEquals(whole, source.read(0, whole.size))
            assertContentEquals(whole.copyOfRange(whole.size - 5, whole.size), source.read(whole.size - 5L, 100))
            assertEquals(0, source.read(0, 0).size)
        } finally {
            source.close()
        }
        // closed, it opens again on the next read
        assertContentEquals(whole.copyOfRange(10, 20), source.read(10, 10))
        source.close()
    }

    @Test
    fun aSinkThatFailsMidWayLeavesNothing() = runTest {
        val path = "$dir$separator$name"
        assertFailsWith<IllegalStateException> {
            FileSink.write<Unit>(path) { sink ->
                sink(frames[1])
                sink(frames[2])
                throw IllegalStateException("the download broke off")
            }
        }
        assertEquals(emptyList(), listDir(dir), "the partial file is removed")
    }

    @Test
    fun anAnswerNotToKeepLeavesNothing() = runTest {
        val path = "$dir$separator$name"
        val answer = FileSink.write(path, kept = { it != null }) { sink ->
            sink(frames[1])
            null
        }
        assertNull(answer)
        assertEquals(emptyList(), listDir(dir), "a write that is not kept leaves nothing")
    }

    @Test
    fun aFileThatDoesNotOpenSaysSo() = runTest {
        val missing = "$dir${separator}nowhere$separator$name"
        assertNull(FileSink.open(missing))
        assertFailsWith<IllegalStateException> { FileSource(missing, name, 1, null, null).read(0, 1) }
    }

    @Test
    fun aPickedDirectoryIsWalkedWithItsHiddenFilesItsLinksReportedAndNeverFollowed() = runTest {
        val root = "$dir${separator}Trip"
        val sub = "$root${separator}Büro 中文"
        val outside = "$dir${separator}outside"
        assertTrue(makeDir(root) && makeDir(sub) && makeDir(outside))
        suspend fun put(path: String, bytes: ByteArray) = FileSink.write(path) { sink -> sink(bytes) }
        put("$root${separator}a.txt", "alpha".encodeToByteArray())
        put("$root$separator.hidden", "h".encodeToByteArray())
        put("$sub$separator$name", whole)
        put("$outside${separator}secret.txt", "never".encodeToByteArray())
        val linked = makeLink(outside, "$root${separator}link-out")
        val locked = "$root${separator}locked"
        assertTrue(makeDir(locked))
        put("$locked${separator}inside.txt", "x".encodeToByteArray())
        val unlistable = makeUnlistable(locked)
        try {
            val found = assertNotNull(PickedPaths.walk(root)).associateBy { it.path }
            val kinds = found.mapValues { it.value.kind }
            assertEquals(me.raddatz.veyq.mobile.depot.FolderUpload.Kind.FILE, kinds["a.txt"])
            assertEquals(me.raddatz.veyq.mobile.depot.FolderUpload.Kind.FILE, kinds[".hidden"], "a hidden file is met")
            assertEquals(me.raddatz.veyq.mobile.depot.FolderUpload.Kind.DIRECTORY, kinds["Büro 中文"])
            assertEquals(me.raddatz.veyq.mobile.depot.FolderUpload.Kind.FILE, kinds["Büro 中文/$name"], "a nested file under its own name")
            if (linked) {
                assertEquals(me.raddatz.veyq.mobile.depot.FolderUpload.Kind.LINK, kinds["link-out"], "a link is reported as one")
                assertNull(kinds["link-out/secret.txt"], "and never followed")
            }
            assertTrue(found.keys.none { it.contains("secret") }, "nothing outside the directory is reached: $kinds")
            if (unlistable) {
                assertEquals(me.raddatz.veyq.mobile.depot.FolderUpload.Kind.UNREADABLE, kinds["locked"], "a directory that does not list is said")
                assertNull(kinds["locked/inside.txt"])
            }
            // a file met is read at its offsets under its own name, its size, type and modified time the system's
            val nested = assertNotNull(found["Büro 中文/$name"]?.source)
            assertEquals(name, nested.name)
            assertEquals(whole.size.toLong(), nested.size)
            assertEquals("application/octet-stream", nested.type)
            assertNotNull(nested.modifiedAt)
            assertContentEquals(whole.copyOfRange(70_000, 70_100), nested.read(70_000, 100))
            nested.close()
            assertEquals("text/plain", found["a.txt"]?.source?.type)
            // what the upload keeps: the three files, the link and the unlistable directory left out and said
            val walked = me.raddatz.veyq.mobile.depot.FolderUpload.walked(found.values.toList())
            // a host that lists every directory anyway (root in the container, Windows) walks into the one meant to be locked
            val kept = listOf(".hidden", "a.txt", "Büro 中文/$name") + if (unlistable) emptyList() else listOf("locked/inside.txt")
            assertEquals(kept.sorted(), walked.entries.map { it.path }.sorted())
            if (linked) assertEquals(listOf("link-out"), walked.links)
            if (unlistable) assertEquals(listOf("locked"), walked.unreadable)
            println("walked on this host: link ${if (linked) "made" else "not made"}, unlistable ${if (unlistable) "made" else "not made"}")
        } finally {
            makeListable(locked)
        }
        // a path that is no directory walks nothing; a file's source is no directory's
        assertNull(PickedPaths.walk("$root${separator}a.txt"))
        assertNull(PickedPaths.source(root))
        assertEquals("Trip", PickedPaths.nameOf(root + separator))
    }
}
