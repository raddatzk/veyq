package me.raddatz.veyq.mobile.crypto

/**
 * A scope-level value's own key `K_v` and its wrap under the scope key (ADR 0115, owner 2026-11-02), a port of the web's
 * `features/spaces/valueKey.ts` over `v1.ts` `wrapValueKey`/`unwrapValueKey`: the wrap is a `value-key` seal under the
 * scope key of an epoch, bound to the scope, that epoch and the value's address (SHA-256 of the value's own context, a
 * plain seal's or a field seal's, [JoinRecords.valueAddress]). Pinned by `v1.json#value_key` and `#seal.sym.value-key`.
 *
 * A rotation re-wraps keys and never rewrites a value ([rewrap]): the same `K_v` under the new key, or — for a wrap that
 * does not open under the old one — a stand-in nobody opens.
 */
class ValueKeys(private val suite: V1) {
    private val joins = JoinRecords(suite)

    /** The `value-key` context [value]'s key is wrapped at under the key of [epoch]: its scope (every value context names
     *  one) and its address. */
    private fun slot(epoch: Long, value: Context): Context {
        val scopeId = value.fields["scopeId"] as? String ?: throw SealContextInvalid("${value.purpose} names no scope")
        val schema = if (value.purpose in Purposes.FIELD) Purposes.FIELD else Purposes.SEAL
        return joins.valueKeyContext(scopeId, epoch, joins.valueAddress(schema, value))
    }

    /** [valueKey] (32 bytes) wrapped under [scopeKey] at [epoch], bound to [value]'s address. */
    fun wrap(scopeKey: ByteArray, epoch: Long, value: Context, valueKey: ByteArray): ByteArray {
        require(valueKey.size == VALUE_KEY_BYTES) { "value key: not 32 bytes" }
        return suite.seal(slot(epoch, value), scopeKey, valueKey)
    }

    /** [value]'s `K_v` opened from [wrapped] with [scopeKey] of [epoch]; throws [OpenRefused] for a wrap of another value,
     *  epoch or scope. The caller zeroes it. */
    fun unwrap(scopeKey: ByteArray, epoch: Long, value: Context, wrapped: ByteArray): ByteArray = suite.open(slot(epoch, value), scopeKey, wrapped)

    /**
     * A rotation's move of one value (the web's `rewrapValueKey`): its `K_v` opened from [wrapped] (base64) under [oldKey]
     * of [oldEpoch] and wrapped, the same bytes, under [newKey] at [newEpoch]. A wrap that does not open is replaced by a
     * stand-in — a random `K_v` at the same slot under a random key dropped at once — so the value reads as unreadable, as
     * before; never a wrap under the new key, which would open a key nobody's value is under. Base64.
     */
    fun rewrap(oldKey: ByteArray, oldEpoch: Long, newKey: ByteArray, newEpoch: Long, value: Context, wrapped: String): String {
        val valueKey = try {
            unwrap(oldKey, oldEpoch, value, unb64(wrapped))
        } catch (e: Exception) {
            return standIn(newEpoch, value)
        }
        try {
            return b64(wrap(newKey, newEpoch, value, valueKey))
        } finally {
            valueKey.fill(0)
        }
    }

    /** A wrap at [value]'s slot of [epoch] that opens under no key anyone holds. */
    private fun standIn(epoch: Long, value: Context): String {
        val dropped = suite.p.randomBytes(32)
        val nobodys = suite.p.randomBytes(VALUE_KEY_BYTES)
        try {
            return b64(wrap(dropped, epoch, value, nobodys))
        } finally {
            dropped.fill(0)
            nobodys.fill(0)
        }
    }

    companion object {
        const val VALUE_KEY_BYTES = 32
    }
}
