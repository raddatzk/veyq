package me.raddatz.veyq.core.crypto

/**
 * One write of a scope-level value that has an author (ADR 0115, owner 2026-11-02): its ciphertext ([value]), sealed
 * under a value key `K_v` its author minted for this write, and that key wrapped under the scope key ([wrappedValueKey],
 * seal purpose `value-key`, bound to the scope, the epoch the write states and the value's address). The two travel and
 * are stored together: a rotation rewrites the wrap only, and a write replaces both. Operator-blind: two opaque
 * ciphertexts the server stores and serves verbatim and never opens.
 */
data class SealedValue(val value: String, val wrappedValueKey: String) {
    companion object {
        /** [value] with its [wrap], or null when neither is given; one without the other is malformed (null either way,
         *  so a caller checks [pairs] first). */
        fun of(value: String?, wrap: String?): SealedValue? = if (value != null && wrap != null) SealedValue(value, wrap) else null

        /** Whether [value] and [wrap] come together or not at all — the wire's rule for a value and its key's wrap. */
        fun pairs(value: String?, wrap: String?): Boolean = (value == null) == (wrap == null)
    }
}
