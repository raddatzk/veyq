package me.raddatz.veyq.mobile.android

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.raddatz.veyq.mobile.android.core.Screen
import me.raddatz.veyq.mobile.android.core.SettingsActions
import me.raddatz.veyq.mobile.android.core.SettingsScreen
import me.raddatz.veyq.mobile.session.AccountSettings
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TrustChange
import me.raddatz.veyq.mobile.session.TrustedDevice
import me.raddatz.veyq.mobile.words.Msg

/**
 * Debug builds only — the account's Settings page (`uc.account.sessions`) drawn over a played account,
 * reached without a server or a sign-in: launched with the extra `veyq.devSettings=1`, the app shows [SettingsScreen]
 * over a fixed state — this device staying signed in, a second device with a saved key, a session the register does not
 * name, a signed-out device with a saved key, "Add a device" offered. Each action plays its effect on that state the way
 * core's `AccountSettings` answers it (`AccountSettingsTest` holds the real rules) and is logged to logcat
 * (`veyq-settings`), so a pass reads what was done before it looks. `veyq.devSettings=refused` starts with the list
 * refused and the register unreadable. A release build has no such door.
 */
object DevSettings {
    fun requested(intent: Intent?): String? = intent?.extras?.getString("veyq.devSettings")?.takeIf { it == "1" || it == "refused" }

    private fun log(line: String) {
        Log.i("veyq-settings", line)
    }

    private val trust = AccountSettings.Trust(TrustedDevice.Method.DEVICE_KEY, StaySignedIn.PRESENCE, listOf(StaySignedIn.PRESENCE, StaySignedIn.LOGIN))
    private const val DAY = 86_400_000L
    private const val NOW = 1_794_000_000_000L // 2026-11-06, 21:20 UTC

    private fun played(variant: String) = if (variant == "refused") AccountSettings.State(
        email = "ada@example.org",
        device = AccountSettings.Device("Pixel 9", null, trust),
        refusal = Msg.ACCOUNT_SETTINGS_LOAD_FAILED,
        registerRefusal = Msg.ACCOUNT_SETTINGS_REGISTER_UNREADABLE,
    ) else AccountSettings.State(
        email = "ada@example.org",
        emailConfirmed = false,
        device = AccountSettings.Device("Pixel 9", "f-here", trust),
        families = listOf(
            AccountSettings.Family("f-here", NOW - 30 * DAY, NOW, current = true, label = "Pixel 9", removesKey = false),
            AccountSettings.Family("f-laptop", NOW - 12 * DAY, NOW - 2 * DAY, current = false, label = "Work laptop", removesKey = true),
            AccountSettings.Family("f-unknown", NOW - 3 * DAY, NOW - 1 * DAY, current = false, label = null, removesKey = false),
        ),
        savedKeyDevices = listOf(AccountSettings.SavedKeyDevice("dev-old", "Old phone")),
        otherSavedKeys = true,
        addDevice = true,
    )

    @Composable
    fun Screen(variant: String) {
        var state by remember { mutableStateOf(played(variant)) }
        val actions = remember {
            object : SettingsActions {
                override fun close() = log("close")
                override fun rename(label: String) {
                    log("rename")
                    val name = label.trim().ifEmpty { return }
                    state = state.copy(
                        device = state.device.copy(label = name),
                        families = state.families?.map { if (it.current) it.copy(label = name) else it },
                    )
                }
                override fun signOut(familyId: String) {
                    log("signOut $familyId")
                    state = state.copy(families = state.families?.filter { it.id != familyId || it.current })
                }
                override fun signOutOthers() {
                    log("signOutOthers")
                    state = state.copy(families = state.families?.filter { it.current })
                }
                override fun signOutAndRemoveKey(familyId: String) {
                    log("signOutAndRemoveKey $familyId")
                    state = state.copy(families = state.families?.filter { it.id != familyId })
                }
                override fun removeSavedKey(deviceId: String) {
                    log("removeSavedKey $deviceId")
                    state = state.copy(savedKeyDevices = state.savedKeyDevices.filter { it.deviceId != deviceId })
                }
                override fun removeEveryOtherSavedKey() {
                    log("removeEveryOtherSavedKey")
                    state = state.copy(
                        savedKeyDevices = emptyList(), otherSavedKeys = false,
                        families = state.families?.map { it.copy(removesKey = false) },
                    )
                }
                override fun stay(level: StaySignedIn, done: (TrustChange) -> Unit) {
                    log("stay $level")
                    state = state.copy(device = state.device.copy(trust = trust.copy(level = level)))
                    done(TrustChange.DONE)
                }
                override fun backToPassword(password: String, done: (TrustChange) -> Unit) {
                    log("backToPassword") // never the password
                    state = state.copy(device = state.device.copy(trust = trust.copy(method = TrustedDevice.Method.DEVICE, level = null)))
                    done(TrustChange.DONE)
                }
                override fun stopTrusting() {
                    log("stopTrusting")
                    state = state.copy(device = state.device.copy(trust = trust.copy(method = null, level = null)))
                }
                override fun signOutHere() = log("signOutHere")
            }
        }
        SettingsScreen(state, actions)
    }
}
