package me.raddatz.veyq.mobile.blob

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files by path (ADR 0130 decision 3) on every native target — macOS, Linux and Windows: [FileSink] writes a file of
 * uneven frames, [FileSource] reads it at offsets and whole, through a name with `ü`, `€` and a CJK character, and the
 * directory as the system lists it ([listDir], the platform's own listing, never the calls under test) holds that name
 * exactly. A sink that fails mid-way leaves nothing behind.
 *
 * The bites, each measured red: Windows' `openFile` over plain `fopen` (the name listed is another); [FileSink.fill]
 * without its removal (the partial file stays).
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
        listDir(dir).forEach { removeFile("$dir$separator$it") }
        removeDir(dir)
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
}
