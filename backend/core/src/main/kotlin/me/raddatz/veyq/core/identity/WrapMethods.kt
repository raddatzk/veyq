package me.raddatz.veyq.core.identity

/**
 * The one classification of Master Key wrap methods — the values of `veyq_master_key_wrap.method`, pinned to the
 * contract's `MasterKeyWrap.method` enum by `WrapMethodTest`. No store spells a method name of its own;
 * `scripts/validate_wrap_method_vocabulary.py` keeps it that way.
 *
 * Every constant states both properties, and neither has a default: that is the structural half of *a method
 * survives only if it is explicitly declared a survivor*. The next method does not compile until someone has
 * decided how a credential change treats it.
 *
 * **Why the credential rewrites keep a whitelist of survivors, not a blacklist of device methods.** Password
 * change, reset and rotation all rewrite what unlocks the Master Key, and each deletes every wrap it cannot
 * rewrite. Only the two **cold** wraps can be rewritten from there, because their KEKs derive from secrets the
 * human holds (the password, the Secret Key) rather than from key material a device holds. A method added later
 * is device-held until proven otherwise, so defaulting it to *deleted* fails safe; a blacklist would silently
 * leave the new method holding the old Master Key — after a rotation that is precisely the compromise the
 * operation exists to end, and after a reset it is a door into the account the person may be resetting
 * *because of* (en.account.key-model § Adding / removing unlock methods).
 *
 * The column stays a `String`: stores compare against these names and never parse a stored value, so a row with
 * an unknown method is simply a non-survivor.
 */
enum class WrapMethod(
    /** Kept by a password change, a reset and a rotation. Everything else is deleted by all three. */
    val survivesCredentialChange: Boolean,
    /** A device-local convenience wrap: the only kind `addWrap` creates and the untrust endpoint may delete. */
    val deviceLocal: Boolean,
) {
    PASSWORD(survivesCredentialChange = true, deviceLocal = false),
    RECOVERY(survivesCredentialChange = true, deviceLocal = false),
    PASSKEY(survivesCredentialChange = false, deviceLocal = false),
    DEVICE(survivesCredentialChange = false, deviceLocal = true),
    DEVICE_KEY(survivesCredentialChange = false, deviceLocal = true),
    ;

    companion object {
        /** The stored names a credential change keeps — every rewrite deletes `method not in` these. */
        val SURVIVORS: List<String> = entries.filter { it.survivesCredentialChange }.map { it.name }

        /** The stored names of the device-local convenience wraps. */
        val DEVICE_LOCAL: Set<String> = entries.filter { it.deviceLocal }.map { it.name }.toSet()
    }
}
