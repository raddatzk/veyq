package me.raddatz.veyq.core.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.URL

/**
 * A body past the request limit answers the contract's `413 ErrorResponse{code: request_too_large}`
 * ([RequestTooLargeRoutes]) on both of its paths:
 *
 * - **declared** — on the header alone, so the probe sends a `Content-Length` and no body;
 * - **of unknown length** — sent chunked, with no `Content-Length`, counted while it is read (inbox #508).
 *
 * Quarkus' own answer to either is a bare `413` with no content type and no body (measured on Quarkus 3.36.0), which is
 * what these tests see when the route is gone (the declared one) or when it hands a chunked body on unread (the chunked
 * one, sent where no bearer is needed: Quarkus' `VertxInputStream` cuts it off while the operation reads it). The controls are the limit itself — a body of exactly 10,240 KiB, either
 * way, is read and answered by the operation (no bearer: `401`), never `413` — and a chunked body under the limit that
 * the operation binds: read here first, it still reaches the binding whole (a well-formed one answers `200`, a malformed
 * one the binding's `400 invalid_request`).
 */
@QuarkusTest
class RequestTooLargeSliceTest {

    @TestHTTPResource("/")
    lateinit var base: URL

    private val mapper = ObjectMapper()

    private class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    /** POST [declared] bytes to an operation that takes a body, sending [send] of them, and read the answer. */
    private fun post(declared: Long, send: Int): Answer = exchange(MEASURES, "Content-Length: $declared") { out ->
        if (send > 0) out.write(ByteArray(send) { ' '.code.toByte() })
    }

    /**
     * POST [body] to [path] chunked — no `Content-Length` — in chunks of at most [CHUNK] bytes, and read the answer. A
     * write the server refused by closing the connection ends the sending; the answer was written before it closed.
     */
    private fun postChunked(path: String, body: ByteArray): Answer = exchange(path, "Transfer-Encoding: chunked") { out ->
        try {
            var at = 0
            while (at < body.size) {
                val n = minOf(CHUNK, body.size - at)
                out.write("${Integer.toHexString(n)}\r\n".toByteArray())
                out.write(body, at, n)
                out.write("\r\n".toByteArray())
                at += n
            }
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: IOException) {
            // The server answered and closed while the body was still being sent — what a refusal of it does.
        }
    }

    private fun exchange(path: String, framing: String, send: (OutputStream) -> Unit): Answer = Socket(base.host, base.port).use { s ->
        s.soTimeout = 60_000
        val out = s.getOutputStream()
        out.write("POST $path HTTP/1.1\r\nHost: ${base.host}\r\nContent-Type: application/json\r\n$framing\r\n\r\n".toByteArray())
        send(out)
        out.flush()
        // Read the head, then exactly the body its Content-Length names: a kept-alive connection never ends the stream.
        val input = s.getInputStream()
        val buffer = StringBuilder()
        while (!buffer.contains("\r\n\r\n")) buffer.append(input.read().also { check(it >= 0) { "closed before a head: $buffer" } }.toChar())
        val lines = buffer.toString().trimEnd().split("\r\n")
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = String(input.readNBytes(headers["content-length"]?.toInt() ?: 0))
        Answer(lines[0].split(' ')[1].toInt(), headers, body)
    }

    private fun assertRequestTooLarge(answer: Answer) {
        assertEquals(413, answer.status)
        assertTrue(answer.headers["content-type"].orEmpty().startsWith("application/json"), "content type: ${answer.headers}")
        assertEquals("close", answer.headers["connection"])
        assertEquals(mapOf("code" to "request_too_large"), mapper.readValue(answer.body, Map::class.java).filterValues { it != null })
    }

    @Test
    fun aBodyDeclaredPastTheLimitAnswersTheContractsErrorResponse() {
        assertRequestTooLarge(post(LIMIT + 1, send = 0))
    }

    @Test
    fun aBodyAtTheLimitIsReadAndAnsweredByTheOperation() {
        val answer = post(LIMIT, send = LIMIT.toInt())
        assertNotEquals(413, answer.status, "a body of exactly the limit is not too large: ${answer.body}")
        assertEquals(401, answer.status, "the operation answered it (no bearer): ${answer.body}")
    }

    /** To an operation that needs no bearer: past authentication the operation reads the body, and Quarkus cuts it off
     *  there with its bare `413` (bitten 2026-10-19: with the chunked path handed on unread, this saw exactly that). */
    @Test
    fun aChunkedBodyPastTheLimitAnswersTheContractsErrorResponse() {
        assertRequestTooLarge(postChunked(CHALLENGE, ByteArray(LIMIT.toInt() + 1) { ' '.code.toByte() }))
    }

    @Test
    fun aChunkedBodyAtTheLimitIsReadAndAnsweredByTheOperation() {
        val answer = postChunked(MEASURES, ByteArray(LIMIT.toInt()) { ' '.code.toByte() })
        assertNotEquals(413, answer.status, "a chunked body of exactly the limit is not too large: ${answer.body}")
        assertEquals(401, answer.status, "the operation answered it (no bearer): ${answer.body}")
    }

    @Test
    fun aChunkedBodyUnderTheLimitReachesTheBindingWhole() {
        val accepted = postChunked(CHALLENGE, """{"email":"kevin@example.com"}""".toByteArray())
        assertEquals(200, accepted.status, "a well-formed chunked body was bound: ${accepted.body}")
        val refused = postChunked(CHALLENGE, """{"email":""".toByteArray())
        assertEquals(400, refused.status, "a malformed chunked body was read and refused by the binding: ${refused.body}")
        assertEquals("invalid_request", mapper.readValue(refused.body, Map::class.java)["code"])
    }

    private companion object {
        /** `quarkus.http.limits.max-body-size` is unset: Quarkus' default, 10,240 KiB. */
        const val LIMIT = 10_240L * 1024
        const val CHUNK = 64 * 1024
        const val MEASURES = "/api/moderation/cases/1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b/measures"
        const val CHALLENGE = "/api/login/challenge"
    }
}
