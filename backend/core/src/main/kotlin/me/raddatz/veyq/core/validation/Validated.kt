package me.raddatz.veyq.core.validation

/**
 * A required request primitive the contract types nullable (`[integer, "null"]`, `[boolean, "null"]`) only so that
 * its absence is visible: a required `type: integer`/`boolean` generates a Kotlin primitive, which Jackson fills with
 * 0/false when the field is missing (`backend/CLAUDE.md`). Its `@NotNull` answers the 400 before the resource runs,
 * so a null here means the request reached the resource unvalidated (a missing `@Valid`) — a bug, so it throws
 * rather than guessing a value.
 */
fun <T : Any> T?.validated(): T =
    checkNotNull(this) { "a @NotNull request field reached the resource as null — is its @Valid missing?" }
