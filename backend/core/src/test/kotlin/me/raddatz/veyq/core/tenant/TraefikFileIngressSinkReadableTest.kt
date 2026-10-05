package com.veyq.core.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The projection is written by the app and read by Traefik, and under ADR 0071 the two run as different uids.
 * `Files.createTempFile` creates its file owner-only and the atomic rename keeps that mode, so without an explicit
 * mode the proxy cannot open the file it routes from, and it fails **silently**: the container stays up and routes
 * nothing. This pins the mode of the file the rename leaves behind, which is the one Traefik reads.
 */
class TraefikFileIngressSinkReadableTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun theWrittenFileIsReadableByAnotherUidAndWritableByNone() {
        assumeTrue("posix" in dir.fileSystem.supportedFileAttributeViews(), "a POSIX mode exists to assert")
        val target = dir.resolve("tenants.yml")
        val sink = TraefikFileIngressSink(
            target, "veyq.example.org", null,
            TraefikFileIngressSink.Upstreams(app = "http://app:8080", blobs = "http://garage:3900", admin = "http://admin:8080"),
            bucket = "blobs",
        )

        sink.apply(listOf("family.example.org"))

        // Control: the write happened, so the mode below is this write's, not an absent file's.
        assertTrue(Files.readString(target).contains("family.example.org"), "the projection was written")
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }
}
