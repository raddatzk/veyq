package com.veyq.core.ratelimit

import com.veyq.core.testing.MainOutputs
import io.quarkus.arc.Arc
import io.quarkus.security.Authenticated
import io.quarkus.test.junit.QuarkusTest
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The mandatory-posture guard of ADR 0051, and the sibling of `DefaultDenyTest`: an endpoint that says nothing
 * about rate limiting fails the build, the way an endpoint that says nothing about auth already fails the request.
 *
 * It replaces a three-endpoint spot-check, and the difference is the point — a spot-check can only pin what
 * someone remembered to list, which is precisely the failure mode here (the missing tenant-invite throttle was
 * found by review, not by a check).
 *
 * **What green proves, and where it stops.** Every endpoint *declares* a posture. It does **not** prove the
 * domain-keyed limiters (`SHARE`/`RECIPIENT`/`OWNER`/`TENANT`) actually **fire** — those are markers the handler
 * enforces, proven per endpoint by a runtime 429 test (`ShareIdentityBoundTest`, `SharePasswordAttemptBoundTest`,
 * `InviteMailRateLimiterTest`) and by nothing here. That boundary is recorded in `validate_guard_gaps.py` and the
 * `trust-chain` skill, not left as a reassuring word.
 *
 * **Enumeration is asked twice, on different keys**, because a sweep that silently misses an endpoint is worse
 * than no sweep: it reads as coverage. Angle 1 walks the **compiled main outputs of every backend project**
 * (`MainOutputs`; complete by construction — it sees every class that was built). Angle 2 asks the **CDI
 * container** which `@Path` beans were actually deployed. A disagreement fails before any posture is checked; agreement is worth something here
 * because the two answer different questions (what was compiled vs. what is live).
 *
 * **The named blind spot:** test-scope resources are excluded, by code source, not by package. `DefaultDenyTest`'s
 * `UnannotatedProbeResource` is a deliberately annotation-free `@Path` class that `@QuarkusTest` deploys like any
 * other — annotating it to satisfy this sweep would destroy the default-deny proof it exists to give.
 */
@QuarkusTest
class RateLimitPostureSweepTest {

    // --- enumeration, angle 1: everything that was compiled into a main output of a backend project --------

    // Every backend project's main output on the classpath, directory or jar (`MainOutputs`): the module split put
    // core's classes into `core.jar` and left the rest in the root's classes directory. Test outputs are not
    // main outputs, which is exactly the boundary that keeps the DefaultDeny probe out.
    private fun compiledResourceClasses(): Set<Class<*>> {
        val loader = Thread.currentThread().contextClassLoader
        return MainOutputs.classNames()
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { isJaxrsResource(it) }
            .toSet()
    }

    // --- enumeration, angle 2: what the CDI container actually deployed --------------------------------------

    private fun deployedResourceClasses(): Set<Class<*>> =
        Arc.container().beanManager()
            .getBeans(Object::class.java, jakarta.enterprise.inject.Any.Literal.INSTANCE)
            .map { it.beanClass }
            .filter { isJaxrsResource(it) && !isTestScoped(it) }
            .toSet()

    // The test-scope exclusion is a file-existence check against the TEST output dir, not a package rule and not a
    // `codeSource` read: Quarkus loads the application's own bean classes through a classloader that reports a
    // null code source, so asking a bean where it came from answers nothing. Asking the filesystem whether the
    // class was compiled into the test tree answers it for every class, and stays independent of the main-dir walk.
    private val testClassesDir = Paths.get(
        RateLimitPostureSweepTest::class.java.protectionDomain.codeSource.location.toURI(),
    )

    private fun isTestScoped(cls: Class<*>): Boolean =
        Files.exists(testClassesDir.resolve(cls.name.replace('.', '/') + ".class"))

    /** A `@Path` on the class or on any interface it implements — the generated `…Api` carries it, not the impl. */
    private fun isJaxrsResource(cls: Class<*>): Boolean =
        !cls.isInterface && !cls.isAnnotation &&
            (cls.isAnnotationPresent(Path::class.java) || cls.interfaces.any { it.isAnnotationPresent(Path::class.java) })

    // --- endpoints + their effective annotations ------------------------------------------------------------

    /** The HTTP-method annotation lives on the generated interface method, so resolve through the hierarchy. */
    private fun isEndpoint(cls: Class<*>, method: Method): Boolean =
        (sequenceOf(method) + interfaceCounterparts(cls, method))
            .any { m -> m.annotations.any { it.annotationClass.java.isAnnotationPresent(HttpMethod::class.java) } }

    private fun interfaceCounterparts(cls: Class<*>, method: Method): Sequence<Method> =
        cls.interfaces.asSequence().mapNotNull {
            runCatching { it.getMethod(method.name, *method.parameterTypes) }.getOrNull()
        }

    private fun endpointsOf(cls: Class<*>): List<Method> =
        cls.methods.filter { !it.isBridge && !it.isSynthetic && it.declaringClass == cls && isEndpoint(cls, it) }

    private fun declaresPermitAll(cls: Class<*>, method: Method?): Boolean =
        method?.isAnnotationPresent(PermitAll::class.java) == true ||
            (method?.isAnnotationPresent(Authenticated::class.java) != true &&
                method?.isAnnotationPresent(RolesAllowed::class.java) != true &&
                cls.isAnnotationPresent(PermitAll::class.java))

    private fun declaresAuthenticated(cls: Class<*>, method: Method?): Boolean {
        method?.let {
            if (it.isAnnotationPresent(Authenticated::class.java) || it.isAnnotationPresent(RolesAllowed::class.java)) return true
            if (it.isAnnotationPresent(PermitAll::class.java)) return false
        }
        return cls.isAnnotationPresent(Authenticated::class.java) || cls.isAnnotationPresent(RolesAllowed::class.java)
    }

    private fun label(cls: Class<*>, method: Method) = "${cls.simpleName}.${method.name}"

    private fun allEndpoints(): List<Pair<Class<*>, Method>> =
        compiledResourceClasses().sortedBy { it.name }.flatMap { cls -> endpointsOf(cls).map { cls to it } }

    // --- the sweep ------------------------------------------------------------------------------------------

    @Test
    fun bothEnumerationsSeeTheSameResources() {
        assertEquals(
            compiledResourceClasses().map { it.name }.sorted(),
            deployedResourceClasses().map { it.name }.sorted(),
            "the compiled @Path classes and the deployed CDI beans disagree — one of the two enumerations is " +
                "blind, and a sweep with a blind enumeration reads as coverage it does not have",
        )
    }

    @Test
    fun theEnumerationIsNotEmpty() {
        // A sweep over nothing passes every assertion below it. This is the tripwire for a broken enumeration.
        val endpoints = allEndpoints()
        assertTrue(endpoints.size > 50, "expected the whole HTTP surface, enumerated only ${endpoints.size}")
    }

    @Test
    fun everyEndpointDeclaresARateLimitPosture() {
        val undeclared = allEndpoints()
            .filter { (cls, m) -> RateLimitPosture.of(cls, m) == RateLimitPosture.Undeclared }
            .map { (cls, m) -> label(cls, m) }
        assertEquals(
            emptyList<String>(), undeclared,
            "every endpoint must carry @RateLimit(key) or @RateLimitExempt(reason) — ADR 0051",
        )
    }

    @Test
    fun everyExemptionStatesANonBlankReason() {
        val blank = allEndpoints()
            .filter { (cls, m) -> (RateLimitPosture.of(cls, m) as? RateLimitPosture.Exempt)?.reason?.isBlank() == true }
            .map { (cls, m) -> label(cls, m) }
        assertEquals(
            emptyList<String>(), blank,
            "a blank reason restores exactly the silence @RateLimitExempt exists to remove",
        )
    }

    @Test
    fun anIpKeyOnlySitsOnAPublicEndpoint() {
        val wrong = allEndpoints()
            .filter { (cls, m) -> RateLimitPosture.of(cls, m) == RateLimitPosture.Limited(RateLimitKey.IP) }
            .filterNot { (cls, m) -> declaresPermitAll(cls, m) }
            .map { (cls, m) -> label(cls, m) }
        assertEquals(
            emptyList<String>(), wrong,
            "key = IP on an authenticated endpoint is the wrong axis: it throttles a legitimate active user, and " +
                "the resolved principal is available there and is the better key",
        )
    }

    @Test
    fun aUserKeyOnlySitsOnAnAuthenticatedEndpoint() {
        val wrong = allEndpoints()
            .filter { (cls, m) -> RateLimitPosture.of(cls, m) == RateLimitPosture.Limited(RateLimitKey.USER) }
            .filterNot { (cls, m) -> declaresAuthenticated(cls, m) }
            .map { (cls, m) -> label(cls, m) }
        assertEquals(
            emptyList<String>(), wrong,
            "key = USER without @Authenticated has no principal to key on — the filter would silently do nothing",
        )
    }

    @Test
    fun aPublicOverrideDeclaresItsOwnPostureInsteadOfInheritingOne() {
        // The hole a class-level default would otherwise leave: a resource whose class is authenticated-and-exempt
        // gains a new @PermitAll method, and that unauthenticated endpoint silently inherits the exemption written
        // for the authenticated surface. Every public override must say its own posture out loud.
        val inherited = allEndpoints()
            .filter { (_, m) -> m.isAnnotationPresent(PermitAll::class.java) }
            .filterNot { (_, m) ->
                m.isAnnotationPresent(RateLimit::class.java) || m.isAnnotationPresent(RateLimitExempt::class.java)
            }
            .map { (cls, m) -> label(cls, m) }
        assertEquals(
            emptyList<String>(), inherited,
            "a method-level @PermitAll must carry a method-level posture — inheriting one written for the " +
                "authenticated surface is how an unauthenticated endpoint ships unbounded and looks decided",
        )
    }
}
