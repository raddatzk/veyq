package me.raddatz.veyq.core.ratelimit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The precedence rules of [RateLimitPosture] (ADR 0051). These matter more than they look: the boundary filters
 * and the posture sweep both resolve through this one object, so a precedence bug does not merely mis-read one
 * endpoint — it makes the guard and the enforcement disagree about what the endpoint declared.
 *
 * Fixtures are plain classes: the resolution is pure reflection over annotations and needs no JAX-RS or Quarkus.
 */
class RateLimitPostureTest {

    @RateLimit(key = RateLimitKey.IP)
    private class ClassLimited {
        fun inherits() = Unit

        @RateLimit(key = RateLimitKey.SHARE)
        fun ownKey() = Unit

        @RateLimitExempt(reason = "this method is the exception")
        fun ownExemption() = Unit
    }

    @RateLimitExempt(reason = "the whole resource is out of scope")
    private class ClassExempt {
        fun inherits() = Unit

        @RateLimit(key = RateLimitKey.USER)
        fun ownKey() = Unit
    }

    private class Bare {
        fun saysNothing() = Unit
    }

    private fun posture(cls: Class<*>, method: String) =
        RateLimitPosture.of(cls, cls.declaredMethods.single { it.name == method })

    @Test
    fun aMethodInheritsTheClassLevelPosture() {
        assertEquals(RateLimitPosture.Limited(RateLimitKey.IP), posture(ClassLimited::class.java, "inherits"))
        assertEquals(
            RateLimitPosture.Exempt("the whole resource is out of scope"),
            posture(ClassExempt::class.java, "inherits"),
        )
    }

    @Test
    fun aMethodLevelKeyOverridesTheClassLevelOne() {
        assertEquals(RateLimitPosture.Limited(RateLimitKey.SHARE), posture(ClassLimited::class.java, "ownKey"))
        assertEquals(RateLimitPosture.Limited(RateLimitKey.USER), posture(ClassExempt::class.java, "ownKey"))
    }

    @Test
    fun aMethodLevelExemptionOverridesAClassLevelKey() {
        assertEquals(
            RateLimitPosture.Exempt("this method is the exception"),
            posture(ClassLimited::class.java, "ownExemption"),
        )
    }

    @Test
    fun sayingNothingIsUndeclaredNotExempt() {
        assertEquals(RateLimitPosture.Undeclared, posture(Bare::class.java, "saysNothing"))
    }

    @Test
    fun enforcedKeyAnswersOnlyForTheFiltersOwnKey() {
        val cls = ClassLimited::class.java
        val method = cls.declaredMethods.single { it.name == "inherits" }
        assertEquals(RateLimitKey.IP, RateLimitPosture.enforcedKey(cls, method, RateLimitKey.IP))
        assertEquals(null, RateLimitPosture.enforcedKey(cls, method, RateLimitKey.USER))

        // A handler-enforced marker is nobody's at the boundary: both filters must pass it through.
        val marker = cls.declaredMethods.single { it.name == "ownKey" }
        assertEquals(null, RateLimitPosture.enforcedKey(cls, marker, RateLimitKey.IP))
        assertEquals(null, RateLimitPosture.enforcedKey(cls, marker, RateLimitKey.USER))
    }

    @Test
    fun onlyIpAndUserAreBoundaryEnforced() {
        assertEquals(
            listOf(RateLimitKey.IP, RateLimitKey.USER),
            RateLimitKey.entries.filter { it.boundaryEnforced },
        )
    }
}
