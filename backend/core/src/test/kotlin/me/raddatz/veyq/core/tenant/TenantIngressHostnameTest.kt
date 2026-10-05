package com.veyq.core.tenant

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The character classes of the one predicate that stands between a stored `tenant.domain` and a reverse
 * proxy's configuration — and, until this class existed, the only part of the ingress projection with no test
 * at all. That absence outlived ADR 0063 and the extraction of the sink seam: the rule the projector's own
 * KDoc calls load-bearing ("a backtick in a domain closes the `Host(` matcher") had never once been executed
 * against a backtick.
 *
 * **A unit test beside the integration one, not instead of it.**
 * `TenantIngressProjectorTest.aRowThatSkippedTheBoundaryIsNeverProjected` proves the real threat end to end —
 * a row written past the API boundary does not reach the file. It can afford one or two such rows. The
 * *shape* has more edges than that, and every one of them is a way for a string to look validated and not be,
 * so they are enumerated here where a case costs nothing.
 *
 * The negatives carry a positive control each by construction: every rejected string below is one edit away
 * from an accepted one, so a predicate that simply returned `false` fails [aWellFormedDomainRoutes] rather
 * than passing everything here.
 */
class TenantIngressHostnameTest {

    @Test
    fun aWellFormedDomainRoutes() {
        // The control the negatives are worthless without.
        assertTrue(TenantIngressProjector.isRoutableHostname("family.example.com"))
        assertTrue(TenantIngressProjector.isRoutableHostname("a.example.com"), "a single-character label")
        assertTrue(TenantIngressProjector.isRoutableHostname("my-tenant.sub.example.co"), "hyphens and depth")
        assertTrue(TenantIngressProjector.isRoutableHostname("t1.example.com"), "digits")
    }

    @Test
    fun aBacktickNeverReachesATraefikRule() {
        // THE case. The rendered rule is `rule: "Host(`$domain`)"` — a backtick in the value closes the matcher
        // and everything after it is Traefik configuration the operator did not write. This is the assertion
        // the projector's KDoc has always claimed and nothing has ever run.
        assertFalse(TenantIngressProjector.isRoutableHostname("evil`.example.com"))
        assertFalse(TenantIngressProjector.isRoutableHostname("a`) || Host(`stolen.example.com"))
    }

    @Test
    fun theShapesThatLookLikeHostnamesAndAreNot() {
        assertFalse(TenantIngressProjector.isRoutableHostname("not_a_hostname.example.com"), "underscore")
        assertFalse(TenantIngressProjector.isRoutableHostname("Example.COM"), "uppercase — the column is lowercased before this runs, so reaching here means it was never normalised")
        assertFalse(TenantIngressProjector.isRoutableHostname("example.com."), "a trailing root dot")
        assertFalse(TenantIngressProjector.isRoutableHostname("localhost"), "a single label has no dot and cannot be a public routing target")
        assertFalse(TenantIngressProjector.isRoutableHostname("example..com"), "an empty label")
        assertFalse(TenantIngressProjector.isRoutableHostname("-lead.example.com"), "a label may not start with a hyphen")
        assertFalse(TenantIngressProjector.isRoutableHostname("trail-.example.com"), "…nor end with one")
        assertFalse(TenantIngressProjector.isRoutableHostname("example.c"), "a one-character TLD")
        assertFalse(TenantIngressProjector.isRoutableHostname("example.c0m"), "a digit in the TLD")
        assertFalse(TenantIngressProjector.isRoutableHostname(""), "the empty string")
        assertFalse(TenantIngressProjector.isRoutableHostname(" family.example.com "), "surrounding whitespace")
    }

    @Test
    fun aNewlineCannotSmuggleASecondLine() {
        // The file sink writes YAML line by line and the Kubernetes sink builds an object name from the same
        // string, so a domain carrying a newline is the injection that reads as two entries in one.
        assertFalse(TenantIngressProjector.isRoutableHostname("good.example.com\nevil.example.com"))
        assertFalse(TenantIngressProjector.isRoutableHostname("good.example.com\n  priority: 9999"))

        // **The TRAILING newline is the one that carries this test.** Measured, by mutating the predicate from
        // `matches` to `containsMatchIn`: the two cases above stayed green, so on their own they pin nothing
        // about which method is used. Without `RegexOption.MULTILINE` — and there is none — `^` and `$` are
        // input anchors rather than line anchors, so no `find` can start a match after an interior newline
        // either way. But Java's `$` also matches *before a line terminator at the end of input*, so
        // `containsMatchIn("good.example.com\n")` is TRUE where `matches` is false: this is the single case
        // that tells the two apart, and a domain ending in a newline is what would otherwise be written
        // straight into the YAML rule.
        assertFalse(TenantIngressProjector.isRoutableHostname("good.example.com\n"), "a trailing newline is not part of a hostname")
        assertFalse(TenantIngressProjector.isRoutableHostname("\ngood.example.com"), "…nor a leading one")
    }
}
