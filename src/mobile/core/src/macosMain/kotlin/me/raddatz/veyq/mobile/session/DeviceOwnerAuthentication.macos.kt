@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.session

import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

// macOS: LocalAuthentication answers.

internal actual fun deviceOwnerAuthenticationAvailable(): Boolean = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)
