package me.raddatz.veyq.mobile.expenses

internal actual fun <V> stringMap(): MutableMap<String, V> = LinkedHashMap()

internal actual fun stringSet(): MutableSet<String> = LinkedHashSet()
