package me.raddatz.veyq.mobile.expenses

internal actual fun <V> stringMap(): MutableMap<String, V> = JsStringMap()

internal actual fun stringSet(): MutableSet<String> = JsStringSet()

/** A `MutableMap` over the engine's `Map`: a string key is hashed natively, iteration is insertion order. */
private class JsStringMap<V> : AbstractMutableMap<String, V>() {
    private val m: dynamic = js("new Map()")

    override val size: Int get() = m.size.unsafeCast<Int>()
    override fun containsKey(key: String): Boolean = m.has(key).unsafeCast<Boolean>()
    override fun get(key: String): V? = m.get(key).unsafeCast<V?>()

    override fun put(key: String, value: V): V? {
        val old = m.get(key).unsafeCast<V?>()
        m.set(key, value)
        return old
    }

    override fun remove(key: String): V? {
        val old = m.get(key).unsafeCast<V?>()
        m.delete(key)
        return old
    }

    override fun clear() {
        m.clear()
    }

    override val entries: MutableSet<MutableMap.MutableEntry<String, V>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<String, V>>() {
            override val size: Int get() = this@JsStringMap.size
            override fun add(element: MutableMap.MutableEntry<String, V>): Boolean = throw UnsupportedOperationException()
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, V>> = object : MutableIterator<MutableMap.MutableEntry<String, V>> {
                // the engine's own live iterator: no copy of the keys, each entry carrying its value
                private val it: dynamic = m.entries()
                private var step: dynamic = it.next()
                private var last: String? = null
                override fun hasNext(): Boolean = !step.done.unsafeCast<Boolean>()
                override fun next(): MutableMap.MutableEntry<String, V> {
                    if (step.done.unsafeCast<Boolean>()) throw NoSuchElementException()
                    val pair = step.value
                    step = it.next()
                    val k = pair[0].unsafeCast<String>()
                    last = k
                    return Entry(k, pair[1].unsafeCast<V>())
                }
                override fun remove() {
                    m.delete(last)
                }
            }
        }

    private inner class Entry(override val key: String, private var current: V) : MutableMap.MutableEntry<String, V> {
        override val value: V get() = current
        override fun setValue(newValue: V): V = current.also { m.set(key, newValue); current = newValue }
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value
        override fun hashCode(): Int = key.hashCode() xor (value?.hashCode() ?: 0)
        override fun toString(): String = "$key=$value"
    }
}

/** A `MutableSet` over the engine's `Set`. */
private class JsStringSet : AbstractMutableSet<String>() {
    private val s: dynamic = js("new Set()")

    override val size: Int get() = s.size.unsafeCast<Int>()
    override fun contains(element: String): Boolean = s.has(element).unsafeCast<Boolean>()

    override fun add(element: String): Boolean {
        if (s.has(element).unsafeCast<Boolean>()) return false
        s.add(element)
        return true
    }

    override fun remove(element: String): Boolean = s.delete(element).unsafeCast<Boolean>()

    override fun clear() {
        s.clear()
    }

    override fun iterator(): MutableIterator<String> = object : MutableIterator<String> {
        private val it: dynamic = s.values()
        private var step: dynamic = it.next()
        private var last: String? = null
        override fun hasNext(): Boolean = !step.done.unsafeCast<Boolean>()
        override fun next(): String {
            if (step.done.unsafeCast<Boolean>()) throw NoSuchElementException()
            val v = step.value.unsafeCast<String>()
            step = it.next()
            last = v
            return v
        }
        override fun remove() {
            s.delete(last)
        }
    }
}
