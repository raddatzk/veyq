package me.raddatz.veyq.mobile.session

/**
 * Whether the device's owner can authenticate to it (a passcode, Touch ID, Face ID: `LAPolicyDeviceOwnerAuthentication`),
 * which both Keychain levels of staying signed in need. LocalAuthentication is on iOS and macOS only: the tvOS SDK has no
 * `LAContext`, and a TV has no passcode, so a TV answers false and stays signed in by the PIN alone (ADR 0160).
 */
internal expect fun deviceOwnerAuthenticationAvailable(): Boolean
