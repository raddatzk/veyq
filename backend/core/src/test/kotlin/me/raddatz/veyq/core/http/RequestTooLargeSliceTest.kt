package me.raddatz.veyq.core.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket
import java.net.URL

/**
 * A body declared past the request limit answers the contract's `413 ErrorResponse{code: request_too_large}`
 * ([RequestTooLargeRoutes]) — on the header alone, so the probe sends a `Content-Length` and no body. Quarkus' own answer
 * to the same request is a bare `413` with no content type and no body (measured on Quarkus 3.36.0), which is what this
 * test sees when the route is gone. The control is the limit itself: a body of exactly 10,240 KiB is read and answered
 * by the operation (no bearer: `401`), never `413`.
 */
@QuarkusTest
class RequestTooLargeSliceTest {

    @TestHTTPResource("/")
    lateinit var base: URL

    private val mapper = ObjectMapper()

    private class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    /** POST [declared] bytes to an operation that takes a body, sending [send] of them, and read the answer. */
    private fun post(declared: Long, send: Int): Answer = Socket(base.host, base.port).use { s ->
        s.soTimeout = 60_000
        val out = s.getOutputStream()
        out.write(
            ("POST /api/moderation/cases/1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b/measures HTTP/1.1\r\nHost: ${base.host}\r\n" +
                "Content-Type: application/json\r\nContent-Length: $declared\r\n\r\n").toByteArray(),
        )
        if (send > 0) out.write(ByteArray(send) { ' '.code.toByte() })
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

    @Test
    fun aBodyDeclaredPastTheLimitAnswersTheContractsErrorResponse() {
        val answer = post(LIMIT + 1, send = 0)
        assertEquals(413, answer.status)
        assertTrue(answer.headers["content-type"].orEmpty().startsWith("application/json"), "content type: ${answer.headers}")
        assertEquals("close", answer.headers["connection"])
        assertEquals(mapOf("code" to "request_too_large"), mapper.readValue(answer.body, Map::class.java).filterValues { it != null })
    }

    @Test
    fun aBodyAtTheLimitIsReadAndAnsweredByTheOperation() {
        val answer = post(LIMIT, send = LIMIT.toInt())
        assertNotEquals(413, answer.status, "a body of exactly the limit is not too large: ${answer.body}")
        assertEquals(401, answer.status, "the operation answered it (no bearer): ${answer.body}")
    }

    private companion object {
        /** `quarkus.http.limits.max-body-size` is unset: Quarkus' default, 10,240 KiB. */
        const val LIMIT = 10_240L * 1024
    }
}
