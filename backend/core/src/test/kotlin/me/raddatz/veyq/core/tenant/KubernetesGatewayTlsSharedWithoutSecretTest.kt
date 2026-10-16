package me.raddatz.veyq.core.tenant

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * `tls.mode=shared` with no `tls.secret-name` is a configuration that cannot mean anything — there is no Secret
 * for the projected objects to reference — and the answer is the one `TenantIngressSinkProducer` already gives
 * an unresolvable namespace: **loud and disabled, never a failed boot** (ADR 0063: `/api/setup` is how every
 * client and the admin surface's first-run setup reach the instance, so a projection must never take it down).
 * Not a silent fallback to `none` either: an instance whose routing works and whose TLS quietly does not is
 * exactly the failure TLS-as-precondition names.
 *
 * `@WithKubernetesTestServer` is here so the namespace IS resolvable — the disabled sink must be disabled for
 * the missing secret and for nothing else. [KubernetesGatewayTlsSharedTest] is the positive control: the same
 * server and mode with the secret named, and there the sink is enabled.
 *
 * **The loudness is asserted by re-running the decision, not by capturing the boot.** The producer runs once,
 * during startup, before any test method can attach a handler; but `ingressSink()` is a plain method on the
 * bean and calling it again re-runs the same decision against the same config, which is what
 * [theRefusalIsLoggedLoudlyNamingTheMissingKey] listens to.
 */
@QuarkusTest
@TestProfile(KubernetesGatewayTlsSharedWithoutSecretTest.SharedWithoutASecret::class)
@WithKubernetesTestServer
class KubernetesGatewayTlsSharedWithoutSecretTest {

    class SharedWithoutASecret : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.tenant.ingress.sink" to "kubernetes",
            "veyq.tenant.ingress.kubernetes.tls.mode" to "shared",
        )
    }

    @Inject
    lateinit var sink: TenantIngressSink

    @Inject
    lateinit var producer: TenantIngressSinkProducer

    @Test
    fun theInstanceStillServesItsUnauthenticatedSetupProbe() {
        // Reaching this line already proves the boot half; the request is the claim the operator cares about.
        given().get("/api/setup").then().statusCode(200)
    }

    @Test
    fun theSinkIsDisabledRatherThanHalfConfigured() {
        // `enabled` rather than bean identity: what is injected is a CDI client proxy for the producer's product.
        assertFalse(sink.enabled, "shared mode with no secret-name must disable the sink, not project without TLS")
    }

    @Test
    fun theRefusalIsLoggedLoudlyNamingTheMissingKey() {
        val logger = Logger.getLogger(TenantIngressSinkProducer::class.java.name)
        val captured = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        val decided = try {
            producer.ingressSink()
        } finally {
            logger.removeHandler(handler)
        }

        assertFalse(decided.enabled, "control: the re-run decision is the same one the boot made")
        assertTrue(
            captured.any { it.level.intValue() >= Level.SEVERE.intValue() && it.message.contains("tls.secret-name") },
            "the refusal must be reported at ERROR and name the key the operator has to set:\n$captured",
        )
    }
}
