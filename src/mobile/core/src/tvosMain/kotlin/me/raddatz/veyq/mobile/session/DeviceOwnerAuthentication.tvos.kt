package me.raddatz.veyq.mobile.session

// tvOS: no LocalAuthentication and no passcode, so neither Keychain level (ADR 0160: the PIN is how a TV stays signed in).

internal actual fun deviceOwnerAuthenticationAvailable(): Boolean = false
