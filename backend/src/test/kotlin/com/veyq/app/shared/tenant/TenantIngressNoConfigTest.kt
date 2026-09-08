package com.veyq.app.shared.tenant

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
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
 */
@QuarkusTest
class TenantIngressNoConfigTest {

    @Inject
    lateinit var projector: TenantIngressProjector

    @Test
    fun regenerateIsANoOpWithNoConfiguredPath() {
        val before = Files.exists(Path.of(TenantIngressProjectorTest.CONFIG_PATH))
        projector.regenerate()
        assertEquals(before, Files.exists(Path.of(TenantIngressProjectorTest.CONFIG_PATH)))
    }
}
