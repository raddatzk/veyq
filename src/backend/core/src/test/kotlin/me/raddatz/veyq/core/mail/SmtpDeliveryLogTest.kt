package me.raddatz.veyq.core.mail

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import me.raddatz.veyq.core.generated.model.DeliveryResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlin.concurrent.thread

/**
 * A failed send logs the mail's kind and the SMTP reply code, never the recipient (ADR 0092 § 3) — neither as a
 * parameter nor inside the cause's message, because a server's refusal echoes the address it refused. The server here
 * is a few lines of SMTP on a loopback socket that refuses every recipient with exactly such a reply.
 *
 * Bites two ways: put `to` back into the `warnf`, or log `e.message` instead of the reply code, and the recipient is in
 * a captured record. The positive controls — the result is FAILED and the line names the kind and the code — keep a
 * send that never reached the server, or a line that is never written, from passing as clean.
 */
@QuarkusTest
class SmtpDeliveryLogTest {

    @Inject
    lateinit var delivery: SmtpDelivery

    @Test
    fun aRefusedRecipientIsLoggedByKindAndCodeNeverByAddress() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) { refuseEveryRecipient(server) }
            val transport = SmtpTransport(
                host = "127.0.0.1", port = server.localPort, username = null,
                passwordCiphertext = null, passwordNonce = null, passwordFingerprint = null, tls = false,
            )

            var result: DeliveryResult? = null
            val emitted = capture {
                result = delivery.deliver(transport, "noreply@media.test", RECIPIENT, "subject", "body", KIND)
            }

            assertEquals(DeliveryResult.FAILED, result)
            assertTrue(emitted.any { it.contains(KIND) && it.contains("SMTP reply 550") }, "the failure line: $emitted")
            emitted.forEach { assertFalse(it.contains(RECIPIENT), "the recipient is in a log record: <$it>") }
        }
    }

    /** Answers one SMTP session: greets, accepts the sender, and refuses the recipient with a reply naming it. */
    private fun refuseEveryRecipient(server: ServerSocket) {
        try {
            server.accept().use { socket ->
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val out = PrintWriter(socket.getOutputStream(), true)
                fun reply(line: String) { out.print("$line\r\n"); out.flush() }
                reply("220 fake.test ESMTP")
                while (true) {
                    val line = input.readLine() ?: return
                    val verb = line.take(4).uppercase()
                    when {
                        verb == "EHLO" || verb == "HELO" -> reply("250 fake.test")
                        verb == "MAIL" -> reply("250 2.1.0 Ok")
                        verb == "RCPT" -> reply("550 5.1.1 <$RECIPIENT>: Recipient address rejected: User unknown")
                        verb == "QUIT" -> { reply("221 2.0.0 Bye"); return }
                        else -> reply("250 Ok")
                    }
                }
            }
        } catch (_: SocketException) {
            // The test closed the server first; nothing to answer.
        }
    }

    /** Every record [SmtpDelivery]'s logger emits during [block], as its message followed by its parameters. */
    private fun capture(block: () -> Unit): List<String> {
        val jul = java.util.logging.Logger.getLogger(SmtpDelivery::class.java.name)
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { synchronized(records) { records.add(record) } }
            override fun flush() {}
            override fun close() {}
        }
        handler.level = Level.ALL
        val previousLevel = jul.level
        jul.level = Level.ALL
        jul.addHandler(handler)
        try {
            block()
        } finally {
            jul.removeHandler(handler)
            jul.level = previousLevel
        }
        return synchronized(records) {
            records.map { r ->
                buildString {
                    append(r.message ?: "")
                    r.parameters?.forEach { append(' ').append(it?.toString() ?: "") }
                }
            }
        }
    }

    private companion object {
        const val RECIPIENT = "guest-person@legal.test"
        const val KIND = "a log-test mail"
    }
}
