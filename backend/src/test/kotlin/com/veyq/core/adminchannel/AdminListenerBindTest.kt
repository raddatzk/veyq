package com.veyq.core.adminchannel

import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * uc.admin.admin-channel `no-admin-listener-no-backend`: a port the listener cannot bind is an exception out of
 * [AdminListener.bind] — and the `StartupEvent` observer that calls it lets it propagate, which is what fails
 * startup. A backend that served its public port with no admin door would look healthy to every probe.
 *
 * Plain JUnit, not `@QuarkusTest`: a failed boot cannot be asserted from inside the application it failed to be.
 * The control is the same call on a free port, so the red cannot be a Vert.x that binds nothing at all.
 */
class AdminListenerBindTest {

    private val vertx = Vertx.vertx()

    @AfterEach
    fun closeVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    @Test
    fun aTakenPortFailsTheBindNamingIt() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val e = assertThrows(IllegalStateException::class.java) {
                AdminListener.bind(vertx, "127.0.0.1", taken.localPort, { it.response().end() })
            }
            assertTrue(e.message!!.contains("127.0.0.1:${taken.localPort}"), e.message)
            assertTrue(e.message!!.contains("does not start"), e.message)
        }
    }

    @Test
    fun aFreePortBinds() {
        val server = AdminListener.bind(vertx, "127.0.0.1", 0, { it.response().end() })
        assertTrue(server.actualPort() > 0)
        server.close().toCompletionStage().toCompletableFuture().get()
    }
}
