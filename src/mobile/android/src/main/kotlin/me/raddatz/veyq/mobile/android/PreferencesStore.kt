package me.raddatz.veyq.mobile.android

import android.content.Context
import me.raddatz.veyq.mobile.session.DeviceStore

/** The device's trust marks in the app's own SharedPreferences: no secret (`TrustMarks`), kept across launches. */
class PreferencesStore(context: Context) : DeviceStore {
    private val prefs = context.getSharedPreferences("veyq.trust", Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
