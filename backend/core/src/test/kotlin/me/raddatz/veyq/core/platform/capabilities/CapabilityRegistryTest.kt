package me.raddatz.veyq.core.platform.capabilities

import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.DEVICE_LINKING
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.GROUP_SPACE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_CONTRIBUTE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_DROP_BOX
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.REMEMBER_DEVICE_PASSWORDLESS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The [CapabilityRegistry] arithmetic, the AND along the path, against switches handed in directly (ADR 0088), and the
 * one check on the register's generation: [CapabilityIds] and [CapabilityTexts] are built from the catalog
 * (`contracts/platform/capabilities.json`, buildSrc's `GenerateCapabilityRegister`), so nothing here pins an id by
 * hand — the test reads the catalog and asks that the build wired the generator to it: every entry, in its order, each
 * with its words. Pure JVM — no CDI needed.
 */
class CapabilityRegistryTest {

    private val catalog = ObjectMapper().readTree(File("../contracts/platform/capabilities.json")).path("capabilities")

    private val catalogIds: List<String> = catalog.fieldNames().asSequence().toList()

    private fun registry(vararg off: String) = CapabilityRegistry({ off.toSet() }, { emptySet() }, CapabilityRequirementValues.NONE, ExternalServiceAddresses.ALL)

    /** A registry with nothing off at the instance level and [tenantOff] off in tenant `t1` only. */
    private fun tenantRegistry(instanceOff: Set<String>, vararg tenantOff: String) =
        CapabilityRegistry({ instanceOff }, { tenant -> if (tenant == "t1") tenantOff.toSet() else emptySet() }, CapabilityRequirementValues.NONE, ExternalServiceAddresses.ALL)

    @Test
    fun theRegisterIsEveryCatalogEntryInCatalogOrderWithItsWords() {
        // Every catalog entry is built (validate_capabilities.py), so every one is advertised, in the catalog's order —
        // the manifest's and the console's. A red here is a generator that read another file or dropped an entry.
        assertTrue(catalogIds.isNotEmpty(), "the catalog was read") // an unread file would make the comparison vacuous
        assertEquals(catalogIds, CapabilityIds.ADVERTISED)
        assertEquals(catalogIds.toSet(), CapabilityTexts.BY_ID.keys)
        catalogIds.forEach { id ->
            val texts = CapabilityTexts.BY_ID.getValue(id)
            assertEquals(catalog.path(id).path("whenOff").path("de").asText(), texts.whenOff.de, "$id: whenOff.de")
            assertEquals(catalog.path(id).path("title").path("en").asText(), texts.title.en, "$id: title.en")
        }
    }

    @Test
    fun anAdvertisedIdsParentEntryIsAdvertisedToo() {
        // The registry ANDs over the entries it knows, which are the advertised ones. A parent the catalog holds but
        // this build does not advertise would drop out of that AND, and its child would be active without it.
        CapabilityIds.ADVERTISED.forEach { id ->
            val parent = id.substringBeforeLast('.', "")
            if (parent in catalogIds) assertTrue(parent in CapabilityIds.ADVERTISED, "'$id' is advertised, its parent '$parent' is not")
        }
    }

    @Test
    fun offSwitchesNameEverySwitchOnThePathRootFirstInstanceBeforeTenantAndAreEmptyExactlyWhenActive() {
        val registry = tenantRegistry(setOf(PUBLIC_SHARES, PUBLIC_SHARES_CONTRIBUTE), PUBLIC_SHARES, REMEMBER_DEVICE)
        assertEquals(
            listOf(
                OffSwitch(SwitchLevel.INSTANCE, PUBLIC_SHARES),
                OffSwitch(SwitchLevel.TENANT, PUBLIC_SHARES),
                OffSwitch(SwitchLevel.INSTANCE, PUBLIC_SHARES_CONTRIBUTE),
            ),
            registry.offSwitches(PUBLIC_SHARES_CONTRIBUTE, "t1"),
        )
        assertEquals(listOf(OffSwitch(SwitchLevel.INSTANCE, PUBLIC_SHARES)), registry.offSwitches(PUBLIC_SHARES, "t2"), "another tenant")
        // A tenant row on an entry no tenant may switch counts for neither answer.
        assertEquals(emptyList<OffSwitch>(), registry.offSwitches(REMEMBER_DEVICE, "t1"))
        // The equivalence the share list rests on (inbox #457), for every entry and both tenants.
        for (tenant in listOf(null, "t1", "t2")) CapabilityIds.ADVERTISED.forEach {
            assertEquals(registry.isActive(it, tenant), registry.offSwitches(it, tenant).isEmpty(), "$it in $tenant")
        }
    }

    @Test
    fun activeIsEveryAdvertisedIdWhenNothingIsSwitchedOff() {
        val registry = registry()
        assertEquals(CapabilityIds.ADVERTISED, registry.active)
        CapabilityIds.ADVERTISED.forEach { assertTrue(registry.isActive(it, null), it) }
        assertFalse(registry.isActive("core.not-built", null), "an id this build does not advertise is never active")
    }

    @Test
    fun aSwitchedOffParentSwitchesOffItsChildrenAndNotItsSiblings() {
        val registry = registry(PUBLIC_SHARES)
        assertFalse(registry.isActive(PUBLIC_SHARES, null))
        assertFalse(registry.isActive(PUBLIC_SHARES_CONTRIBUTE, null), "a child needs its parent")
        assertFalse(registry.isActive(PUBLIC_SHARES_DROP_BOX, null), "a child needs its parent")
        assertTrue(registry.isActive(GROUP_SPACE, null), "a sibling entry stays active")
        assertEquals(
            CapabilityIds.ADVERTISED - setOf(PUBLIC_SHARES, PUBLIC_SHARES_CONTRIBUTE, PUBLIC_SHARES_DROP_BOX),
            registry.active,
            "active keeps the advertised order, minus the switched-off entry and everything below it",
        )
    }

    @Test
    fun aSwitchedOffChildLeavesItsParentAndItsSiblingActive() {
        val registry = registry(PUBLIC_SHARES_CONTRIBUTE)
        assertFalse(registry.isActive(PUBLIC_SHARES_CONTRIBUTE, null))
        assertTrue(registry.isActive(PUBLIC_SHARES, null), "the AND runs up the path, never down")
        assertTrue(registry.isActive(PUBLIC_SHARES_DROP_BOX, null), "a sibling child stays active")
    }

    @Test
    fun aPrefixThatIsNoEntryIsNoAncestor() {
        // `core` is a root segment, not an entry: naming it switches nothing off.
        assertEquals(CapabilityIds.ADVERTISED, registry("core").active)
    }

    @Test
    fun theSwitchesAreAskedOnEveryCall() {
        var off = emptySet<String>()
        val registry = CapabilityRegistry({ off }, { emptySet() }, CapabilityRequirementValues.NONE, ExternalServiceAddresses.ALL)
        assertTrue(registry.isActive(DEVICE_LINKING, null))
        off = setOf(DEVICE_LINKING)
        assertFalse(registry.isActive(DEVICE_LINKING, null), "a switch acts without a new registry, so without a restart")
        assertFalse(DEVICE_LINKING in registry.active)
    }

    @Test
    fun aTenantSwitchNarrowsThatTenantAlongThePathAndNoOtherTenant() {
        val registry = tenantRegistry(emptySet(), PUBLIC_SHARES)
        assertFalse(registry.isActive(PUBLIC_SHARES, "t1"))
        assertFalse(registry.isActive(PUBLIC_SHARES_DROP_BOX, "t1"), "a child needs its parent at the tenant level too")
        assertTrue(registry.isActive(GROUP_SPACE, "t1"), "a sibling stays active in the tenant")
        // Controls: the same switch reaches neither another tenant nor the instance level.
        assertTrue(registry.isActive(PUBLIC_SHARES_DROP_BOX, "t2"), "another tenant is untouched")
        assertTrue(registry.isActive(PUBLIC_SHARES_DROP_BOX, null), "the instance level is untouched")
        assertEquals(CapabilityIds.ADVERTISED - setOf(PUBLIC_SHARES, PUBLIC_SHARES_CONTRIBUTE, PUBLIC_SHARES_DROP_BOX), registry.active("t1"))
        assertEquals(CapabilityIds.ADVERTISED, registry.active)
    }

    @Test
    fun aTenantCannotWidenWhatTheInstanceSwitchedOff() {
        val registry = tenantRegistry(setOf(GROUP_SPACE))
        assertFalse(registry.isActive(GROUP_SPACE, "t1"), "the instance off wins over a tenant that left it on")
        val entry = registry.tenantRegister("t1").single { it.id == GROUP_SPACE }
        assertEquals(TenantCapabilityState(GROUP_SPACE, off = false, serverOff = true, effective = false), entry)
    }

    @Test
    fun aTenantRowForAnInstanceOnlyEntryIsIgnored() {
        // The write refuses one; were a row there anyway, it must not take a device switch away from the Server Owner.
        val registry = tenantRegistry(emptySet(), REMEMBER_DEVICE, DEVICE_LINKING)
        assertTrue(registry.isActive(REMEMBER_DEVICE, "t1"))
        assertTrue(registry.isActive(DEVICE_LINKING, "t1"))
    }

    @Test
    fun theTenantRegisterListsTheTenantSwitchableEntriesInCatalogOrder() {
        val tenantLevel = catalogIds.filter { id -> catalog.path(id).path("levels").any { it.asText() == "tenant" } }
        assertTrue(tenantLevel.isNotEmpty(), "the catalog names tenant-switchable entries")
        assertEquals(tenantLevel.toSet(), CapabilityIds.TENANT_SWITCHABLE, "the generator read the catalog's levels")
        val registry = tenantRegistry(emptySet(), PUBLIC_SHARES_CONTRIBUTE)
        assertEquals(tenantLevel, registry.tenantRegister("t1").map { it.id })
        val contribute = registry.tenantRegister("t1").single { it.id == PUBLIC_SHARES_CONTRIBUTE }
        assertEquals(TenantCapabilityState(PUBLIC_SHARES_CONTRIBUTE, off = true, serverOff = false, effective = false), contribute)
    }

    private fun requirementRegistry(instance: Map<String, Int>, t1: Map<String, Int>) = CapabilityRegistry(
        { emptySet() }, { emptySet() },
        object : CapabilityRequirementValues {
            override fun instance() = instance
            override fun tenant(tenantId: String) = if (tenantId == "t1") t1 else emptyMap()
        },
        ExternalServiceAddresses.ALL,
    )

    @Test
    fun theStrictestOfBothLevelsIsInEffectAndATenantOnlyTightens() {
        val days = CapabilityRequirements.LINK_MAX_DAYS
        val flag = CapabilityRequirements.LINK_PASSWORD
        val registry = requirementRegistry(mapOf(days to 30), mapOf(days to 60, flag to 1))
        // The tenant's looser 60 days is not in effect; its password flag is, in t1 only.
        assertEquals(mapOf(days to 30, flag to 1), registry.requirements("t1"))
        assertEquals(mapOf(days to 30), registry.requirements("t2"))
        assertEquals(mapOf(days to 30), registry.requirements(null))
        assertEquals(mapOf(days to 7), requirementRegistry(mapOf(days to 30), mapOf(days to 7)).requirements("t1"))
        val listed = registry.tenantRequirements("t1", PUBLIC_SHARES).associate { it.def.name to Triple(it.value, it.serverValue, it.effective) }
        assertEquals(mapOf(flag to Triple(1, null, 1), days to Triple(60, 30, 30)), listed)
    }

    @Test
    fun aTenantValueOfAnInstanceOnlyRequirementIsIgnored() {
        val registry = requirementRegistry(emptyMap(), mapOf(CapabilityRequirements.PASSWORD_MAX_DAYS to 1))
        assertEquals(emptyMap<String, Int>(), registry.requirements("t1"))
    }

    @Test
    fun theGeneratorReadTheCatalogsRequirements() {
        val fromCatalog = catalogIds.flatMap { id -> catalog.path(id).path("requirements").fieldNames().asSequence().map { it to id }.toList() }
        assertTrue(fromCatalog.isNotEmpty(), "the catalog declares requirements")
        assertEquals(fromCatalog, CapabilityRequirements.ALL.map { it.name to it.capabilityId })
        CapabilityRequirements.ALL.forEach { r ->
            assertEquals(catalog.path(r.capabilityId).path("requirements").path(r.name).path("whenSet").path("de").asText(), r.whenSet.de)
            assertEquals(catalog.path(r.capabilityId).path("requirements").path(r.name).path("kind").asText().uppercase(), r.kind.name)
        }
    }

    @Test
    fun anExternalServiceWithoutAnAddressIsNotActiveAndItsSwitchCannotBringItBack() {
        val noMap = ExternalServiceAddresses {
            (CapabilityIds.EXTERNAL_SERVICES - CapabilityIds.MEMORIES_MAP).associateWith { ServiceAddress("https://x.example/", null) }
        }
        val registry = CapabilityRegistry({ emptySet() }, { emptySet() }, CapabilityRequirementValues.NONE, noMap)
        assertFalse(registry.isActive(CapabilityIds.MEMORIES_MAP, null))
        assertTrue(registry.isActive(CapabilityIds.MEMORIES_PLACES, null), "control: a configured service is active")
        assertEquals(CapabilityState(CapabilityIds.MEMORIES_MAP, off = false, effective = false), registry.register().single { it.id == CapabilityIds.MEMORIES_MAP })
        assertTrue(registry.tenantRegister("t1").single { it.id == CapabilityIds.MEMORIES_MAP }.serverOff, "the server has it off for every tenant")
    }

    @Test
    fun theOriginOfAServiceAddressIsItsSchemeHostAndPort() {
        assertEquals("https://tile.openstreetmap.org", ExternalServiceOrigins.origin("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertEquals("http://tiles.local:8080", ExternalServiceOrigins.origin("http://tiles.local:8080/{z}/{x}/{y}.png"))
        assertEquals(null, ExternalServiceOrigins.origin("ftp://geo.example.com"))
        assertEquals(null, ExternalServiceOrigins.origin("/relative/{z}"))
    }
}
