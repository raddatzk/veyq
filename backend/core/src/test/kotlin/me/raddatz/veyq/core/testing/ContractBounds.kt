package me.raddatz.veyq.core.testing

import java.io.File

/**
 * A response array's `maxItems` as the contract states it, read from `contracts/api` — so a test that fills a list past a
 * number can assert the answer still fits its own schema. The generated Kotlin models carry no bound of a response
 * (only a request's `x-field-extra-annotation` reaches the code), so nothing else in a slice test sees one.
 *
 * Reads the house layout only: a schema at four spaces under `components.schemas`, its property at eight, the
 * property's keys at ten. A schema or property it cannot find is a failure, never a pass.
 */
object ContractBounds {

    /** [schema].[property]'s `maxItems` in `contracts/api/[spec]`, or null when the contract sets none. */
    fun maxItems(spec: String, schema: String, property: String): Int? {
        val lines = File("../contracts/api/$spec").readLines()
        val start = lines.indexOf("    $schema:").also { check(it >= 0) { "no schema $schema in $spec" } }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].matches(Regex("^ {4}\\S.*")) } ?: lines.size
        val prop = (start + 1 until end).firstOrNull { lines[it] == "        $property:" }
            ?: error("no property $schema.$property in $spec")
        val propEnd = (prop + 1 until end).firstOrNull { lines[it].matches(Regex("^ {8}\\S.*")) } ?: end
        return (prop + 1 until propEnd).firstNotNullOfOrNull { Regex("^ {10}maxItems: (\\d+)$").find(lines[it])?.groupValues?.get(1)?.toInt() }
    }

    /** Fails unless [count] items fit [schema].[property] as the contract bounds it. */
    fun assertFits(spec: String, schema: String, property: String, count: Int) {
        val bound = maxItems(spec, schema, property) ?: return
        if (count > bound) throw AssertionError("$schema.$property answered $count items, but the contract caps it at $bound")
    }
}
