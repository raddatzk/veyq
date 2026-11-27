package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.search.SettingsSection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Settings page's order is the web's (ADR 0154 decision 2, cr-apps-structure-from-core-plans): read here from the
 * web's own source, `features/settings/sections.ts`, so neither side moves alone (as `MediaTierSpecTest` holds the tiers).
 * A kind's header order and a header's and a row's plans are no longer read from the web's source: the web draws them from
 * the core (`@veyq/core-js/navigation`), both held to `contracts/platform/plans/v1-vectors.json` (`PlanVectorsTest`).
 */
class PlansAreTheWebsTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val web = File(System.getProperty("veyq.repoRoot"), "src/web/src")

    private fun read(path: String) = File(web, path).readText()

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
                SettingsSection.ERASE, SettingsSection.WHOS_WHO, SettingsSection.TERMS, SettingsSection.SIGN_OUT,
            ),
            all,
        )
    }

    /**
     * The tenant's sections the apps offer are the web's tenant category's, in its order (`sections.ts`' `key: 'tenant'`),
     * after the personal ones: Who's who (`tenant-people`) before the terms (inbox #967).
     */
    @Test
    fun theTenantSectionsAreTheWebsInTheirOrder() {
        val text = read("features/settings/sections.ts")
        val tenant = text.substring(text.indexOf("key: 'tenant'"), text.indexOf("key: 'groups'"))
        val web = Regex("""\bid:\s*'([a-z-]+)'""").findAll(tenant).map { it.groupValues[1] }.toList()
        val all = SettingsSection.offered(SettingsSection.Gates(addDevice = false, storage = false, maps = false, appearance = false))
        val offered = all.map { it.id }.filter { it in web }
        assertEquals(listOf("tenant-people", "terms"), offered, "the tenant sections the apps offer")
        assertEquals(web.filter { it in offered }, offered, "in the web's tenant order (sections.ts)")
        assertEquals(listOf(SettingsSection.WHOS_WHO, SettingsSection.TERMS, SettingsSection.SIGN_OUT), all.takeLast(3))
    }

    /**
     * The tenant's terms are a section of the web's tenant category, after the personal ones (`sections.ts`' `key: 'tenant'`):
     * the apps' page is over one tenant, so its one tenant section follows the personal ones there too (uc.account.accept-terms).
     */
    @Test
    fun theTermsAreTheWebsTenantSectionAfterThePersonalOnes() {
        val text = read("features/settings/sections.ts")
        val tenant = text.substring(text.indexOf("key: 'tenant'"), text.indexOf("key: 'groups'"))
        assertTrue(Regex("""\bid:\s*'terms'""").containsMatchIn(tenant), "the web's tenant category offers its terms")
        val all = SettingsSection.offered(SettingsSection.Gates(addDevice = false, storage = false, maps = false, appearance = false))
        assertEquals(listOf(SettingsSection.TERMS, SettingsSection.SIGN_OUT), all.takeLast(2), "the terms after the personal sections, Sign out last")
    }
}
