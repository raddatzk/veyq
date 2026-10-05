package me.raddatz.veyq.core.tenant

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * **An instance whose Kubernetes API server is unreachable must still serve.** The same contract
 * `TenantIngressUnwritablePathTest` holds for the file sink, at a different failure mode: a projection is a
 * routing convenience, `/api/setup` is how every client — and the admin surface's first-run setup — reaches
 * the instance at all, so the first must never be able to take down the second (ADR 0063).
 *
 * It is worth asserting separately from the file sink's version because the two fail for unrelated reasons and
 * a fix for one says nothing about the other: that one was an `AccessDeniedException` from a call sitting
 * above its `try`, this one is a socket that never connects, from a client whose retry and timeout behaviour
 * is a library's and not ours. The api-server-url points at port 1, which nothing listens on.
 *
 * **Two halves, and the first is why the config is set in the profile.** The overrides are applied before the
 * application boots, so `TenantIngressProjector.onStartup` runs against the dead endpoint: if the sink
 * propagated, this `@QuarkusTest` could not start at all.
 *
 * **Reading that first half: do NOT trust the word PASS** — measured on this class, by making
 * `KubernetesIngressSink.apply`'s catch rethrow. The report came back as *"3 tests, 1 failure"*, and the two
 * methods that read as **passing** are the two that never ran: Gradle attributes the boot error to whichever
 * method the extension was constructing ([applyDoesNotPropagate], `t=0.001`) and writes the rest as ordinary
 * `<testcase>` elements with no failure child and `t=0.0`. The signals that distinguish are the task's exit
 * code and the per-method durations, exactly as `TenantIngressUnwritablePathTest` records for the file sink's
 * equivalent. Anyone re-biting this class should check those, not the status column.
 *
 * This class also pins the config binding: `veyq.tenant.ingress.sink=kubernetes` is hyphenated property text
 * mapped onto an enum constant, and nothing else in the tree would notice if that mapping broke — with no
 * Kubernetes sink selected there would be no API call to fail, and every assertion below would pass vacuously.
 * [theFailureIsLoggedLoudly] is what makes that impossible: it fails unless the sink really tried.
 */
@QuarkusTest
@TestProfile(KubernetesIngressSinkUnreachableTest.PointedAtAnUnreachableApiServer::class)
class KubernetesIngressSinkUnreachableTest {

    class PointedAtAnUnreachableApiServer : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.tenant.ingress.sink" to "kubernetes",
            // The one legitimate use of the namespace override: outside a cluster there is no ServiceAccount to
            // read one from, and without it the producer would (correctly) hand back DisabledIngressSink — which
            // would make this class prove nothing at all.
            "veyq.tenant.ingress.kubernetes.namespace" to "veyq-unreachable",
            "quarkus.kubernetes-client.api-server-url" to "http://127.0.0.1:1",
            // Without these the client's defaults turn a deliberate failure into a ~minute of retries per call.
            "quarkus.kubernetes-client.connection-timeout" to "PT0.5S",
            "quarkus.kubernetes-client.request-timeout" to "PT0.5S",
            "quarkus.kubernetes-client.request-retry-backoff-limit" to "0",
        )
    }

    @Inject
    lateinit var projector: TenantIngressProjector

    @Inject
    lateinit var sink: TenantIngressSink

    @Test
    fun theInstanceStillServesItsUnauthenticatedSetupProbe() {
        // Reaching this line already proves the startup half — the class could not have booted otherwise. The
        // request is what makes the claim the operator cares about: not merely "the JVM came up" but "the
        // endpoint every client starts at answers".
        given().get("/api/setup").then().statusCode(200)
    }

    @Test
    fun applyDoesNotPropagate() {
        // The runtime path: a tenant mutation calls this after its own transaction has committed. Throwing here
        // would fail an operation whose row is already written.
        assertDoesNotThrow { sink.apply(listOf("a.example.com")) }
        assertDoesNotThrow { projector.regenerate() }
    }

    @Test
    fun theFailureIsLoggedLoudly() {
        // Degrading silently is the same defect as throwing, one shift later: the domain does not resolve and
        // nothing says why. This is also the positive control for the whole class — it fails if the sink never
        // attempted the call, which is what a broken config binding would look like.
        val logger = Logger.getLogger(KubernetesIngressSink::class.java.name)
        val captured = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            sink.apply(listOf("a.example.com"))
        } finally {
            // Restored in the same edit that installs it: a handler left on a JVM-wide logger would keep
            // collecting from every later test class in this run.
            logger.removeHandler(handler)
        }

        assertTrue(
            captured.any { it.level.intValue() >= Level.SEVERE.intValue() && it.message.contains("routing is stale") },
            "an unreachable API server must be reported at ERROR, naming the consequence:\n$captured",
        )
    }
}
