package me.raddatz.veyq.core.validation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The contracts' `format: email`, as [MailboxValidator] reads it, at its boundaries — both ways (inbox #510). Every case
 * below was measured on 2026-10-19 against the validator the API fuzzing reads the format with (`jsonschema_rs` 0.59 in
 * the pinned Schemathesis image, `validator_for({"format": "email"}, validate_formats=True)`); the two lists agree with
 * it case for case, except the last list's, where that validator is laxer than RFC 5321 and this keeps the RFC.
 */
class MailboxTest {

    private fun dom(total: Int): String = generateSequence(total) { it - 64 }.takeWhile { it > 0 }
        .map { "x".repeat(minOf(63, it)) }.joinToString(".").also { check(it.length in total - 1..total + 1) }

    private val accepted = listOf(
        "a@b.c", "a@b", "a@localhost", "user.name+tag@example.com", "-@so.test", "a.b.c@x.org", "A@X.ORG",
        "!#$%&'*+/=?^_`{|}~-@x.org", "a@xn--4ca.org", "a@a--b.org", "a@a-b-c.org", "a@1x.org", "a@x.123", "a@123.org",
        "a@1.2.3.4", "a@0", "a@x.o", "a@x.c0m",
        // A local part of 64 characters, a label of 63, a domain of 253.
        "a".repeat(64) + "@x.org", "a@" + "x".repeat(63) + ".org", "a@" + "x".repeat(63) + "." + "x".repeat(63) + "." +
            "x".repeat(63) + "." + "x".repeat(61),
        // Quoted local parts: any printable character but `"` and `\` raw, either of them escaped, a visible one escaped.
        "\"quoted\"@x.org", "\"a b\"@x.org", "\"\"@x.org", "\"a.b\"@x.org", "\"a@b\"@x.org", "\" \"@x.org", "\"!\"@x.org",
        "\"[]\"@x.org", "\"a\\\"b\"@x.org", "\"a\\\\b\"@x.org", "\"\\a\"@x.org", "\"\\~\"@x.org", "\"\\@\"@x.org",
        "\"" + "a".repeat(62) + "\"@x.org",
        // Address literals: IPv4 and IPv6 (the tag in any case), the forms RFC 5321 names.
        "a@[127.0.0.1]", "a@[0.0.0.0]", "a@[255.255.255.255]", "a@[IPv6:::1]", "a@[ipv6:::1]", "a@[IPv6:::]",
        "a@[IPv6:2001:db8::1]", "a@[IPv6:1:2:3:4:5:6:7:8]", "a@[IPv6:abcd::EF01]", "a@[IPv6:::ffff:1.2.3.4]",
        "a@[IPv6:1:2:3:4:5:6:1.2.3.4]",
    )

    private val refused = listOf(
        // Hibernate's @Email took both (the fuzzing's finding, inbox #510): a `*` in a domain label.
        "-@so.test*", "u@t.org*",
        "", "a", "@x.org", "a@", "a@@x.org", "a@x@y.org", "a..b@x.org", ".a@x.org", "a.@x.org", "a b@x.org",
        "a(comment)@x.org", " a@x.org", "a@x.org ", "a@x.org\n", "\u0000@x.org", "a\\b@x.org", "a.\"b\"@x.org",
        // Domains: a hyphen at either end, an underscore, an empty label, a trailing dot, a space, punctuation.
        "a@-x.org", "a@x-.org", "a@x.-o", "a@xn--.org", "a@x_y.org", "a@x..org", "a@.x.org", "a@x.org.", "a@x .org",
        "a@x,org", "a@x;org",
        // Non-ASCII anywhere — that is `idn-email`.
        "ä@x.org", "a@ä.org", "a@xä.org", "a@ä", "\"ä\"@x.org",
        // Lengths: a local part of 65, a label of 64, a domain of 254 or more.
        "a".repeat(65) + "@x.org", "a@" + "x".repeat(64) + ".org", "a@" + dom(255), "\"" + "a".repeat(63) + "\"@x.org",
        // Quoted strings: unescaped `"`, an escaped space or control character, a lone quote, DEL.
        "\"a\"b\"@x.org", "\"\"\"@x.org", "\"a\\ b\"@x.org", "\"\\ \"@x.org", "\"\\\u0001\"@x.org", "\"a\u007f\"@x.org",
        "\"unterminated@x.org",
        // Address literals: out-of-range, leading zeros, too few or too many parts, a zone, a tag nobody registered.
        "a@[300.0.0.1]", "a@[256.1.1.1]", "a@[01.2.3.4]", "a@[1.2.3.04]", "a@[1.2.3]", "a@[1.2.3.4.5]", "a@[1.2.3.4 ]",
        "a@[]", "a@[1.2.3.4", "a@1.2.3.4]", "a@[::1]", "a@[tag:text]", "a@[x-y:abc]", "a@[IPv6: ::1]",
        "a@[IPv6:12345::1]", "a@[IPv6:1::2::3]", "a@[IPv6:1:2:3:4:5:6:7:8:9]", "a@[IPv6:fe80::1%eth0]",
        "a@[IPv6:1:2:3:4:5:6:7:1.2.3.4]",
    )

    /** Laxer in the fuzz image's validator than RFC 5321, which this keeps: a raw tab is no `qtextSMTP`, and a `::`
     *  stands for at least two groups (so at most six beside it, four beside an IPv4 tail). */
    private val refusedByTheRfcOnly = listOf(
        "\"a\tb\"@x.org", "a@[IPv6:1:2:3:4:5:6:7::]", "a@[IPv6:1:2:3:4:5::1.2.3.4]",
    )

    @Test
    fun theBoundaryCasesTheFormatAdmitsAreAccepted() {
        val wrong = accepted.filterNot(MailboxValidator::isMailbox)
        assertTrue(wrong.isEmpty(), "refused, but the format admits them: $wrong")
    }

    @Test
    fun theBoundaryCasesTheFormatRefusesAreRefused() {
        val wrong = (refused + refusedByTheRfcOnly).filter(MailboxValidator::isMailbox)
        assertTrue(wrong.isEmpty(), "accepted, but the format refuses them: $wrong")
    }
}
