package me.raddatz.veyq.mobile.qr

/** A file of the repository read whole: through `java.io` on the JVM, POSIX on the native targets. */
expect fun readRepoFile(path: String): ByteArray

/** The QR decoder's own fixtures (`core/src/commonTest/resources/qr`, written by `core/qr/make_fixtures.py`). */
object QrFixtures {
    /** The texts the fixtures' codes hold, as `make_fixtures.py` has them. */
    val texts = mapOf(
        "pair" to "veyqpair:3q2-7wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4",
        "signin" to "veyqsignin:Zm9vYmFyLWJhei1xdXV4LWNvcmdlLWdyYXVsdC1nYXJwbHk",
        "kit" to "veyqkit:eyJ2IjozLCJlbWFpbCI6Inpvw6subcO8bGxlckBleGFtcGxlLmNvbSIsInNrIjoiMEM1MTI2LTBaNFJQSy04RVQyOTUtODVFUUs1LURIU1FOMC1DOEhZQjktVjk1QlBBLVdXMUhZRS1UUUUwTkQiLCJzcnYiOiJodHRwczovL3ZleXEuZXhhbXBsZS5jb20ifQ",
    )

    /** A fixture as a frame: a binary PGM (`P5`, 8-bit). */
    fun frame(name: String): QrFrame = pgm(readRepoFile("src/mobile/core/src/commonTest/resources/qr/$name.pgm"))

    fun pgm(bytes: ByteArray): QrFrame {
        // The header: "P5", width, height, maxval, each after whitespace, then one whitespace byte and the pixels.
        var at = 0
        fun token(): String {
            while (bytes[at].toInt().toChar().isWhitespace()) at++
            val start = at
            while (!bytes[at].toInt().toChar().isWhitespace()) at++
            return bytes.copyOfRange(start, at).decodeToString()
        }
        check(token() == "P5") { "not a binary PGM" }
        val width = token().toInt()
        val height = token().toInt()
        check(token() == "255") { "not an 8-bit PGM" }
        at++
        return QrFrame(width, height, width, bytes.copyOfRange(at, at + width * height))
    }
}
