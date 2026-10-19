package me.raddatz.veyq.core.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * An address as the contracts' `format: email` reads it — the one definition the server and the contract share (inbox
 * #510). Written beside every request property the contract declares `format: email`, as
 * `@field:me.raddatz.veyq.core.validation.Mailbox` in its `x-field-extra-annotation`; `validate_request_bounds.py`
 * holds the two together, both ways. It replaces Hibernate's `@Email`, which took addresses the format refuses (a
 * domain with a `*` in it, any non-ASCII character) — the API fuzzing sent them as malformed and they were accepted.
 *
 * JSON Schema's `email` is RFC 5321's `Mailbox`: a dot-string or quoted-string local part, `@`, a domain of
 * letter-digit-hyphen labels or an address literal. Where the validator the API fuzzing reads the format with
 * (`jsonschema_rs` 0.59, in the pinned Schemathesis image) is stricter than RFC 5321, this takes its reading, so every
 * address the fuzzing draws as malformed is refused here too: a quoted pair escapes a visible character only (not a
 * space), an IPv4 literal has no leading zero, a domain is at most 253 characters. Where it is laxer (a raw tab inside
 * quotes, a `::` for a single IPv6 group) this keeps RFC 5321. ASCII only — an internationalized address is the other
 * format, `idn-email`. `MailboxTest` holds the boundary cases both ways, as measured against that validator.
 *
 * A null value is not this annotation's to judge — the field's presence is its `@NotNull`.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [MailboxValidator::class])
annotation class Mailbox(
    val message: String = "not an email address",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class MailboxValidator : ConstraintValidator<Mailbox, String?> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean = value == null || isMailbox(value)

    companion object {
        private const val ATEXT = "!#$%&'*+-/=?^_`{|}~"
        private const val MAX_LOCAL = 64
        private const val MAX_DOMAIN = 253
        private const val MAX_LABEL = 63

        /** Whether [value] is an RFC 5321 `Mailbox`, read as the class comment says. */
        fun isMailbox(value: String): Boolean {
            if (value.any { it.code !in 0x20..0x7e }) return false
            val at = localPartEnd(value) ?: return false
            if (at == 0 || at > MAX_LOCAL || at >= value.length || value[at] != '@') return false
            return domain(value.substring(at + 1))
        }

        /** The index just past the local part — a quoted string or a dot-string — or null when there is none. */
        private fun localPartEnd(s: String): Int? {
            if (s.startsWith('"')) {
                var i = 1
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c == '"' -> return i + 1
                        c == '\\' -> if (i + 1 < s.length && s[i + 1].code in 33..126) i += 2 else return null
                        c.code == 32 || c.code == 33 || c.code in 35..91 || c.code in 93..126 -> i++
                        else -> return null
                    }
                }
                return null
            }
            val end = s.indexOf('@').takeIf { it > 0 } ?: return null
            val atoms = s.substring(0, end).split('.')
            return end.takeIf { atoms.all { atom -> atom.isNotEmpty() && atom.all { it.isLetterOrDigit() || it in ATEXT } } }
        }

        private fun domain(d: String): Boolean = when {
            d.startsWith('[') && d.endsWith(']') && d.length > 2 -> addressLiteral(d.substring(1, d.length - 1))
            else -> d.length in 1..MAX_DOMAIN && d.split('.').all(::label)
        }

        /** A letter-digit-hyphen label: 1–63 characters, a letter or digit at both ends. */
        private fun label(l: String): Boolean =
            l.length in 1..MAX_LABEL && l.first().isLetterOrDigit() && l.last().isLetterOrDigit() &&
                l.all { it.isLetterOrDigit() || it == '-' }

        private fun addressLiteral(a: String): Boolean =
            if (a.length > 5 && a.substring(0, 5).equals("IPv6:", ignoreCase = true)) ipv6(a.substring(5)) else ipv4(a)

        /** Four decimal octets, each 0–255 with no leading zero. */
        private fun ipv4(a: String): Boolean = a.split('.').let { parts ->
            parts.size == 4 && parts.all { p ->
                p.length in 1..3 && p.all { it in '0'..'9' } && (p == "0" || p[0] != '0') && p.toInt() <= 255
            }
        }

        /**
         * RFC 5321's IPv6 forms: eight groups; or a `::` with at most six groups beside it (it stands for two or more);
         * and either with an IPv4 address in place of the last two groups (six groups, or at most four beside a `::`).
         */
        private fun ipv6(a: String): Boolean {
            val lastColon = a.lastIndexOf(':')
            val v4 = lastColon >= 0 && '.' in a.substring(lastColon + 1)
            val groups = when {
                !v4 -> a
                !ipv4(a.substring(lastColon + 1)) -> return false
                a.substring(0, lastColon + 1).endsWith("::") -> a.substring(0, lastColon + 1)
                else -> a.substring(0, lastColon)
            }
            val hex = { g: String -> g.length in 1..4 && g.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } }
            val compressed = groups.indexOf("::")
            if (compressed < 0) return groups.split(':').let { gs -> gs.size == (if (v4) 6 else 8) && gs.all(hex) }
            if (groups.indexOf("::", compressed + 1) >= 0) return false
            val sides = listOf(groups.substring(0, compressed), groups.substring(compressed + 2))
                .map { side -> if (side.isEmpty()) emptyList() else side.split(':') }
            return sides.all { it.all(hex) } && sides.sumOf { it.size } <= (if (v4) 4 else 6)
        }

        private fun Char.isLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
