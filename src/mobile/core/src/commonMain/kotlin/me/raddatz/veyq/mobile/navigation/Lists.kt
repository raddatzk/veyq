package me.raddatz.veyq.mobile.navigation

/**
 * An object the app can open: a node of one application in one scope. Its name is the plaintext the app decrypted;
 * it never leaves the device.
 */
data class ObjectRef(
    val scopeId: String,
    val nodeId: String,
    val kind: String,
    val application: ApplicationId,
    val name: String,
)

/** When this device last opened an object (ADR 0122 decision 5: Recent is per install, never synced). */
data class Use(val ref: ObjectRef, val lastUsedEpochMs: Long)

/** One list arranged by ADR 0122 decision 4. */
data class ArrangedList(
    val favorites: List<ObjectRef>,
    val recent: List<ObjectRef>,
    val all: List<ObjectRef>,
)

/**
 * Every list follows one rule: Favorites, then Recent, then All, each ordered by last use, an object listed once
 * (ADR 0122 decision 4). An object never used on this device sorts after every used one, by name.
 *
 * [favoritesLimit] and [recentLimit] cut the start's short lists; the application's own list passes none.
 */
fun arrange(
    favorites: Collection<ObjectRef>,
    uses: Collection<Use>,
    all: Collection<ObjectRef> = emptyList(),
    favoritesLimit: Int? = null,
    recentLimit: Int? = null,
): ArrangedList {
    val lastUse = HashMap<Pair<String, String>, Long>()
    uses.forEach { u ->
        val k = u.ref.key()
        lastUse[k] = maxOf(lastUse[k] ?: Long.MIN_VALUE, u.lastUsedEpochMs)
    }
    val byUse = compareByDescending<ObjectRef> { lastUse[it.key()] ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() }

    val fav = favorites.distinctBy { it.key() }.sortedWith(byUse).let { l -> favoritesLimit?.let { l.take(it) } ?: l }
    val favKeys = favorites.map { it.key() }.toSet()

    val rec = uses.sortedByDescending { it.lastUsedEpochMs }
        .map { it.ref }
        .distinctBy { it.key() }
        .filterNot { it.key() in favKeys }
        .let { l -> recentLimit?.let { l.take(it) } ?: l }
    val shown = favKeys + rec.map { it.key() }

    val rest = all.distinctBy { it.key() }.filterNot { it.key() in shown }.sortedWith(byUse)
    return ArrangedList(fav, rec, rest)
}

private fun ObjectRef.key() = scopeId to nodeId
