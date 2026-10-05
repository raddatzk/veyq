package me.raddatz.veyq.core.adminchannel

import me.raddatz.veyq.core.generated.AdminChannelOperations
import me.raddatz.veyq.core.identity.ServerRoles.SERVER_OWNER
import me.raddatz.veyq.core.testing.MainOutputs
import io.quarkus.security.Authenticated
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * uc.admin.admin-channel `the-gate-implies-the-marker` (ADR 0065): the channel's operation set is the contract's
 * `x-veyq-channel: admin` markers, generated at build time — and this holds it to the backend's Server-Owner gate
 * in both directions. An operation whose effective gate is the Server-Owner role and that carries no marker would
 * ship on the public origin; a marker that names no resource method is a gate on nothing, and reads as coverage.
 *
 * **The effective gate is the method's annotation, else the class's** — the same resolution the security layer
 * applies, and the one `TenantsResource` relies on: its class-level `@RolesAllowed(SERVER_OWNER)` is what gates the
 * eight tenant-administration methods that carry no annotation of their own.
 *
 * Enumeration walks the compiled main classes of every backend project (`MainOutputs`), as
 * `RateLimitPostureSweepTest` does (whose second, CDI angle already proves that walk sees every deployed resource). Plain JUnit: nothing here needs the application.
 *
 * **What green does not prove:** that the filter refuses. That is `AdminChannelGateSliceTest`'s — this proves
 * only that the set the filter reads is the set the gate implies. `createServerOwner` is in the set on purpose and
 * is not role-gated; it is the anonymous operation the channel exists for, so it is not derivable from any gate.
 */
class AdminChannelPostureSweepTest {

    private fun resourceClasses(): List<Class<*>> {
        val loader = Thread.currentThread().contextClassLoader
        return MainOutputs.classNames()
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { !it.isInterface && it.interfaces.any { i -> i.isAnnotationPresent(Path::class.java) } }
            .sortedBy { it.name }
    }

    private fun endpointsOf(cls: Class<*>): List<Method> =
        cls.methods.filter { m ->
            !m.isBridge && !m.isSynthetic && m.declaringClass == cls &&
                cls.interfaces.any { i ->
                    runCatching { i.getMethod(m.name, *m.parameterTypes) }.getOrNull()
                        ?.annotations?.any { it.annotationClass.java.isAnnotationPresent(HttpMethod::class.java) } == true
                }
        }

    /** The roles the effective gate demands: the method's own annotation wins; otherwise the class's. */
    private fun effectiveRoles(cls: Class<*>, m: Method): Set<String> {
        if (m.isAnnotationPresent(PermitAll::class.java) || m.isAnnotationPresent(Authenticated::class.java)) return emptySet()
        m.getAnnotation(RolesAllowed::class.java)?.let { return it.value.toSet() }
        return cls.getAnnotation(RolesAllowed::class.java)?.value?.toSet().orEmpty()
    }

    private val endpoints by lazy { resourceClasses().flatMap { cls -> endpointsOf(cls).map { cls to it } } }

    @Test
    fun theEnumerationAndTheSetAreNotEmpty() {
        // A sweep over nothing passes both assertions below — the tripwire for a broken walk or a lost marker set.
        assertTrue(endpoints.size > 50, "expected the whole HTTP surface, enumerated only ${endpoints.size}")
        assertTrue("createServerOwner" in AdminChannelOperations.operationIds, "the generated set lost setup: ${AdminChannelOperations.operationIds}")
    }

    @Test
    fun everyServerOwnerGatedOperationCarriesTheMarker() {
        val unmarked = endpoints
            .filter { (cls, m) -> SERVER_OWNER in effectiveRoles(cls, m) }
            .filter { (_, m) -> m.name !in AdminChannelOperations.operationIds }
            .map { (cls, m) -> "${cls.simpleName}.${m.name}" }
        assertEquals(
            emptyList<String>(), unmarked,
            "a Server-Owner-gated operation without `x-veyq-channel: admin` in its spec answers on the public origin " +
                "(ADR 0065) — mark the operation in contracts/api",
        )
    }

    @Test
    fun everyMarkerNamesAResourceMethodThatExists() {
        val implemented = endpoints.map { (_, m) -> m.name }.toSet()
        assertEquals(
            emptyList<String>(), (AdminChannelOperations.operationIds - implemented).sorted(),
            "a marked operationId that no resource implements gates nothing — the marker or the resource is wrong",
        )
    }
}
