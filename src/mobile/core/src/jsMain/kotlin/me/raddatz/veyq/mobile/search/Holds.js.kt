package me.raddatz.veyq.mobile.search

internal actual fun String.holds(t: String): Boolean = asDynamic().includes(t).unsafeCast<Boolean>()
