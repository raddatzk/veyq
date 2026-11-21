package me.raddatz.veyq.mobile.navigation

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializer
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.search.SettingsSection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The plans' orders are the web's (ADR 0154 decision 2, cr-apps-structure-from-core-plans): each is read here from the
 * web's own source — a kind's header priority from its registration (`header.order`), core's merge order from
 * `coreNodeActions.ts`, the Settings page's sections from `features/settings/sections.ts` — so neither side moves alone
 * (as `MediaTierSpecTest` holds the tiers).
 */
class PlansAreTheWebsTest {
    private val web = File(System.getProperty("veyq.repoRoot"), "src/web/src")

    private fun read(path: String) = File(web, path).readText()

    /** A [HeaderAction]'s web id: its serial name. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun id(a: HeaderAction): String = serializer<HeaderAction>().descriptor.getElementName(a.ordinal)

    /** The ids of the first `header: { order: [...] }` in [text]. */
    private fun order(text: String, where: String): List<String> {
        val m = assertNotNull(Regex("""header:\s*\{\s*order:\s*\[([^\]]*)]""").find(text), "a header order in $where")
        return Regex("""'([^']+)'""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    /** The registration of [constant] in [file]: from its `app.kind(CONSTANT` to the next `app.kind(`, else the whole file. */
    private fun registration(file: String, constant: String?): String {
        val text = read(file)
        if (constant == null) return text
        val at = text.indexOf("app.kind($constant")
        if (at < 0) return text
        val next = text.indexOf("app.kind(", at + 1)
        return if (next < 0) text.substring(at) else text.substring(at, next)
    }

    @Test
    fun eachKindsHeaderOrderIsItsRegistrations() {
        // the kind, the file that registers it, and its constant there where the file registers more than one
        val kinds = listOf(
            Triple("FOLDER", "core/applications/registry.ts", null),
            Triple("SHARE_BOOKMARK", "core/bookmark/kind.ts", null),
            Triple("MEMORY", "features/memories/register.ts", null),
            Triple("CHAPTER", "features/memories/register.ts", null),
            Triple("FILE", "features/depot/register.ts", "FILE_KIND"),
            Triple("DEPOT_DROPBOX", "features/depot/register.ts", "DROPBOX_KIND"),
            Triple("EXPENSES_POT", "features/expenses/register.ts", null),
        )
        for ((kind, file, constant) in kinds) {
            val text = registration(file, constant)
            val web = order(text, "$file ($kind)")
            assertEquals(web, AppKinds.headerOrder(kind).map(::id), "$kind's header order against $file")
        }
    }

    @Test
    fun coresMergeOrderIsCoreNodeActions() {
        val text = read("core/node/coreNodeActions.ts")
        val header = text.substring(text.indexOf("    header(node) {"), text.indexOf("    row(node) {"))
        // the order each core action's builder is spread into the header's list
        val web = Regex("""\b(favorite|copy|download|report)HeaderActions\b""").findAll(header).map { it.groupValues[1] }.toList()
        assertEquals(listOf("favorite", "copy", "download", "report"), web, "the web's header merge order as read")
        // the apps' core actions the web's header has, in the order the core merges them (the cover and "Available
        // offline" are the apps' own menu entries: a web header's cover is its visual, its offline is no web feature)
        assertEquals(web, NodeHeader.CORE_ORDER.map(::id).filter { it in web })
    }

    @Test
    fun theSettingsSectionsAreTheWebsInTheirOrder() {
        val text = read("features/settings/sections.ts")
        val personal = text.substring(text.indexOf("key: 'personal'"), text.indexOf("cats.push("))
        val web = Regex("""\bid:\s*'([a-z-]+)'""").findAll(personal).map { it.groupValues[1] }.toList()
        // the web's id where the apps name a section differently; the web's sections the apps do not offer yet
        val named = mapOf("pairing" to "add-device")
        val notOffered = setOf("trust-reset", "moderation")
        val shared = web.filter { it !in notOffered }.map { named[it] ?: it }
        val all = SettingsSection.offered(SettingsSection.Gates(addDevice = true, storage = true, maps = true, appearance = true))
        assertEquals(shared, all.map { it.id }.filter { it in shared }, "the web's sections in the web's order (sections.ts)")
        assertTrue(shared.all { s -> all.any { it.id == s } }, "every section the web offers the apps offer: $shared")
        // the app-only sections beside their kin (owner 2026-11-20, the CR's Q3), Sign out last
        assertEquals(
            listOf(
                SettingsSection.APPEARANCE, SettingsSection.MAPS, SettingsSection.STORAGE, SettingsSection.SESSIONS,
                SettingsSection.DEVICE, SettingsSection.SAVED_KEYS, SettingsSection.ACCOUNT, SettingsSection.EMAIL,
                SettingsSection.PASSWORD, SettingsSection.ADD_DEVICE, SettingsSection.SECURITY, SettingsSection.PROFILE,
                SettingsSection.ERASE, SettingsSection.SIGN_OUT,
            ),
            all,
        )
    }
}
