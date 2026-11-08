package me.raddatz.veyq.mobile.expenses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Pot's settings — its base currency and its own categories — as the `pot` object of the node's sealed meta, a port of
 * `src/web/src/features/expenses/domain/potMeta.ts`; `contracts/expenses/pot-meta.v1.schema.json` is the source of truth.
 * A Pot whose `pot` object does not read shows no balance and takes no money entry.
 */
data class PotMeta(val base: Ledger.BaseCurrency, val categories: List<Pair<String, String>>) {
    /** A category's name: a default's id (the platform translates it) or the Pot's own name, null for an unknown own id. */
    fun ownName(id: String): String? = categories.firstOrNull { it.first == id }?.second

    /** The `pot` object a writer stores (`potMetaObject`), held to the reader first: a writer never seals what its
     *  reader refuses ([IllegalArgumentException] then). */
    fun toJson(): JsonObject {
        val out = buildJsonObject {
            put("v", 1)
            put("baseCurrency", base.currency)
            put("baseMinorUnits", base.minorUnits)
            if (categories.isNotEmpty()) put("categories", buildJsonArray {
                for ((id, name) in categories) add(buildJsonObject { put("id", id); put("name", name) })
            })
        }
        read(out)
        return out
    }

    companion object {
        const val KEY = "pot"
        const val MAX_OWN = 200
        const val MAX_NAME = 100

        /** The `pot` object of an opened node-meta envelope, or null when it carries none that reads. */
        fun of(envelope: JsonObject): PotMeta? = runCatching { read(envelope[KEY]) }.getOrNull()

        fun read(raw: JsonElement?): PotMeta {
            val o = raw as? JsonObject ?: throw IllegalArgumentException("/")
            fun num(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
            require(num("v") == 1L) { "/v" }
            val currency = (o["baseCurrency"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { RevisionFormat.CURRENCY.matches(it) }
                ?: throw IllegalArgumentException("/baseCurrency")
            val minorUnits = num("baseMinorUnits")?.takeIf { it in 0..4 } ?: throw IllegalArgumentException("/baseMinorUnits")
            val categories = mutableListOf<Pair<String, String>>()
            o["categories"]?.let { c ->
                val list = c as? JsonArray ?: throw IllegalArgumentException("/categories")
                require(list.size <= MAX_OWN) { "/categories" }
                list.forEachIndexed { i, x ->
                    val item = x as? JsonObject ?: throw IllegalArgumentException("/categories/$i")
                    val id = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { RevisionFormat.UUID.matches(it) }
                        ?: throw IllegalArgumentException("/categories/$i/id")
                    val name = (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.takeIf { RevisionFormat.codePoints(it) in 1..MAX_NAME } ?: throw IllegalArgumentException("/categories/$i/name")
                    if (categories.none { it.first == id }) categories += id to name
                }
            }
            return PotMeta(Ledger.BaseCurrency(currency, minorUnits.toInt()), categories)
        }
    }
}
