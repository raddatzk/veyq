package com.veyq.app.shared.tenant

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The other half of the projector's configuration contract, and it needs the **default** `%test` config — which
 * is why it cannot live in [TenantIngressProjectorTest], whose profile is precisely the one that turns the
 * projector on.
 *
 * With no `dynamic-config-path` the projector is a no-op: not degraded, not deferred, *nothing*. That is what
 * lets a self-hoster run the backend with no reverse proxy at all, and it is also why the `@Observes
 * StartupEvent` regenerate can never be a boot requirement — every other `@QuarkusTest` in this suite boots
 * through that observer under exactly this configuration, so a projector that threw here would take the whole
 * suite with it.
 *
 * **[theConfiguredSinkIsTheDisabledOne] is here because [regenerateIsANoOpWithNoConfiguredPath] cannot see the
 * defect it describes.** Measured, by wiring `TenantIngressSinkProducer` to fall back to a real file sink
 * instead of [DisabledIngressSink]: the projection was written and this class stayed green, because
 * `onStartup`'s regenerate runs **before** the test method takes its `before` snapshot — so `before` and
 * `after` are both `true` and the comparison holds for the wrong reason. The file assertion is still the
 * statement of the behaviour and stays; the sink assertion is the one that can actually fail, and it is immune
 * to that ordering because it reads a decision rather than a side effect.
 */
@QuarkusTest
class TenantIngressNoConfigTest {

    @Inject
    lateinit var projector: TenantIngressProjector

    @Inject
    lateinit var sink: TenantIngressSink

    @Test
    fun regenerateIsANoOpWithNoConfiguredPath() {
        val before = Files.exists(Path.of(TenantIngressProjectorTest.CONFIG_PATH))
        projector.regenerate()
        assertEquals(before, Files.exists(Path.of(TenantIngressProjectorTest.CONFIG_PATH)))
    }

    @Test
    fun theConfiguredSinkIsTheDisabledOne() {
        // Asserted on `enabled` rather than on the identity of the bean: what is injected is a CDI client proxy
        // for the producer's product, so `assertSame(DisabledIngressSink, sink)` would fail on a correct
        // instance. `enabled` is the projector's own short-circuit, read through the proxy like any other call.
        assertFalse(sink.enabled, "with no dynamic-config-path there is no proxy to project into, so there is no sink")
    }
}
