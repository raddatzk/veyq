@file:OptIn(ExperimentalSerializationApi::class)

package me.raddatz.veyq.mobile.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No key, seal, token or secret crosses the bridge (ADR 0123's Consequences, the bridge): no generated type carries a
 * field named like one, and none carries bytes — bytes the UI shows travel beside the JSON, never in it. The check
 * walks what the generator walks ([Schema]), so a type added to the wire is checked without a list of its own.
 *
 * Two kinds of name are let through, each by its owner and field, each with why:
 * - inward, what the person typed on its way to the core — the sign-in's password and Secret Key, the unlock's
 *   password, and the password "Manage servers" and Settings take back to the password trust, which cross once and never
 *   come back;
 * - an identifier, a catalog key or a flag that only shares the word: a connection's key (`host#userId`, it names an
 *   account), a bar place's key (the catalog's name of its words), a trusted device's wrap id (the server's name for the
 *   wrap) and Settings' two flags that say whether removing a saved key is offered.
 *
 * A guest link's address is a key by its nature — `K_share` rides its fragment (`#k=`) — and still crosses outward,
 * since it is what the person copies and hands out. That is the one allowance of its kind, written once ([linkAddresses])
 * and held by name: any outward field named like an address (`url`, `address`) not listed there is refused. A public
 * page's address (the privacy notice a report points to) carries no key; it is listed apart ([publicAddresses]), by name too.
 */
class SecretFreeSchemaTest {
    private val typedIn = mapOf(
        "Session.SignIn.password" to "the password the person typed, on its way to the core",
        "Session.SignIn.secretKey" to "the Secret Key the person typed, on its way to the core",
        "Session.Unlock.password" to "the password the person typed at the unlock, on its way to the core",
        "Manage.PasswordTrust.password" to "the password the person typed to go back to the password trust, on its way to the core",
        "Share.Password.password" to "the password a guest link is to ask for, as the person typed it, on its way to the core",
        "Settings.PasswordTrust.password" to "the password the person typed on Settings to go back to the password trust, on its way to the core",
    )
    /** The outward fields that carry a guest link's address, its key in the fragment, each with why. */
    private val linkAddresses = mapOf(
        "me.raddatz.veyq.mobile.bridge.ShareShown.ShownLink.address" to "an open link's address, which the person copies or shares from its row",
        "me.raddatz.veyq.mobile.bridge.ShareShown.freshAddress" to "the address just minted or renewed, shown once to copy or share",
    )
    /** The outward fields that carry the address of a public page, no key in it, each with why. */
    private val publicAddresses = mapOf(
        "me.raddatz.veyq.mobile.bridge.ReportShown.privacyUrl" to "the server's privacy notice at its passage on a reporter's contact: a public page, no fragment key",
    )
    private val identifiers = mapOf(
        "Signed.connectionKey" to "a connection's id, host#userId: it names an account and opens nothing",
        "me.raddatz.veyq.mobile.navigation.BarTab.key" to "the catalog's name of a bar place's words",
        "me.raddatz.veyq.mobile.session.TrustedDevice.wrapId" to "the id the server gave this device's wrap: it names the wrap, holds none of it",
        "SettingsShown.otherSavedKeys" to "whether Settings offers to remove the other devices' saved keys: a flag, no key",
        "SettingsFamily.removesKey" to "whether a session's row offers \"Sign out and remove saved key\": a flag, no key",
    )

    @Test
    fun nothingTheCoreSendsCarriesAKeyASealATokenASecretOrBytes() {
        assertEquals(emptyList(), leaks(Roots.outward, identifiers))
        assertEquals(emptyList(), addresses(Roots.outward, linkAddresses + publicAddresses))
        // each listed address is a field the wire has
        val fields = Schema(Roots.all).let { s -> s.types.values.flatMap { t -> s.fields(t).map { "${it.owner}.${it.name}" } } }.toSet()
        assertEquals(emptySet(), (linkAddresses + publicAddresses).keys - fields)
    }

    /** The address check against what it must refuse: an outward `url` or `…Address` not listed is red, a listed one is not. */
    @Test
    fun anAddressNotListedIsRefused() {
        assertEquals(listOf("Addressed.url: a link's address", "Addressed.shareAddress: a link's address"), addresses(listOf(Addressed.serializer()), linkAddresses))
        assertEquals(emptyList(), addresses(listOf(Addressed.serializer()), mapOf("Addressed.url" to "t", "Addressed.shareAddress" to "t")))
    }

    @Serializable @kotlinx.serialization.SerialName("Addressed")
    class Addressed(val name: String, val url: String?, val shareAddress: String)

    private fun addresses(roots: List<KSerializer<*>>, allowed: Map<String, String>): List<String> {
        val schema = Schema(roots)
        return schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }
            .map { "${it.owner}.${it.name}" to it.name }
            .filter { (at, name) -> ADDRESS.containsMatchIn(name) && at !in allowed }
            .map { (at, _) -> "$at: a link's address" }
    }

    /**
     * A device link (ADR 0136's Consequences) crosses as its state alone: no field of any shape is named like the
     * pairing's secrets (the code's text, the channel's handle or key, the grant, its envelope, the transport key), and
     * the link's own outward shapes carry exactly these fields — the code itself is a picture beside the JSON
     * (`Link.Code`), the five-digit number and the six digits are on screen by design. A field added to them is red here
     * until it is named below, with why.
     */
    @Test
    fun aDeviceLinkCrossesAsItsStateAlone() {
        assertEquals(emptyList(), pairingNamed(Roots.all))
        val schema = Schema(Roots.all)
        val fields = schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }
            .groupBy({ it.owner }, { it.name })
        assertEquals(
            mapOf(
                "LinkShown" to listOf("role", "step", "number", "code", "label", "refusal", "hint"),
                "Link" to listOf("link", "shown"),
                "LinkOpened" to listOf("link"),
            ),
            mapOf(
                "LinkShown" to fields["me.raddatz.veyq.mobile.bridge.LinkShown"].orEmpty() + fields["LinkShown"].orEmpty(),
                "Link" to fields.entries.single { (owner, names) -> owner.endsWith("Link") && "shown" in names }.value,
                "LinkOpened" to fields.entries.single { (owner, _) -> owner.endsWith("LinkOpened") }.value,
            ),
        )
        // the check against what it must refuse
        assertEquals(listOf("Paired.handle", "Paired.payload", "Paired.grantEnvelope", "Paired.channel", "Paired.transport"), pairingNamed(listOf(Paired.serializer())))
    }

    /**
     * What the offline store adds to the wire crosses as sizes and times alone (ADR 0140 decision 10, the CR's phase 3): a
     * screen offline says as of when (`OfflineAsOf`: the shell's state, a gallery's), the Settings section how much is
     * kept against the limit (`AccountSettings.Storage`) — never a key, a seal, an entry's name or what it holds. A field
     * added to either is red here until it is named below, with why; and each is reached from the wire's roots.
     */
    @Test
    fun theOfflineStoreCrossesAsSizesAndTimesAlone() {
        val schema = Schema(Roots.all)
        val fields = schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }.groupBy({ it.owner }, { it.name })
        assertEquals(listOf("asOf"), fields["OfflineAsOf"], "a screen offline says more than as of when")
        assertEquals(
            listOf("limit", "automatic", "pinned", "free"),
            fields.entries.single { (owner, _) -> owner.endsWith("AccountSettings.Storage") }.value,
            "the Settings section's storage says more than sizes",
        )
        // where they ride: the shell's state, a gallery's, the Settings page's
        val holders = fields.filterValues { names -> "offline" in names || "storage" in names }.keys.map { it.substringAfterLast('.') }.toSet()
        assertTrue(holders.containsAll(setOf("State", "GalleryShown", "SettingsShown")), "the offline states ride elsewhere: $holders")
    }

    @Serializable @kotlinx.serialization.SerialName("Paired")
    class Paired(val number: String, val handle: String, val payload: String, val grantEnvelope: String, val channel: String, val transport: String)

    private fun pairingNamed(roots: List<KSerializer<*>>): List<String> {
        val schema = Schema(roots)
        return schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }
            .filter { PAIRING.containsMatchIn(it.name) }.map { "${it.owner}.${it.name}" }
    }

    @Test
    fun whatTheHostSendsCarriesNoneButWhatThePersonTyped() {
        assertEquals(emptyList(), leaks(Roots.inward, identifiers + typedIn))
        // and each exception still names a field the wire has: one that is gone leaves no hole to fill unseen
        val fields = Schema(Roots.all).let { s -> s.types.values.flatMap { t -> s.fields(t).map { "${it.owner}.${it.name}" } } }.toSet()
        assertEquals(emptySet(), (typedIn + identifiers).keys - fields)
    }

    /**
     * The check itself, against what it must refuse: a field `sealKey: ByteArray` is red twice — its name and its
     * bytes.
     */
    @Test
    fun aStateThatCarriesASealKeyIsRefused() {
        assertEquals(
            listOf("Leaky.sealKey: named like a secret", "Leaky.sealKey: bytes"),
            leaks(listOf(Leaky.serializer()), identifiers),
        )
        assertEquals(listOf("Leaky2.refreshToken: named like a secret"), leaks(listOf(Leaky2.serializer()), identifiers))
        assertTrue(leaks(listOf(Leaky3.serializer()), identifiers).single().endsWith("password: named like a secret"))
    }

    /**
     * Bytes the UI shows ride beside the JSON (`Answer.Bytes`), never in it (ADR 0137 decision 1): a byte array is
     * refused wherever it sits — a field, a list's element, a map's value — and whatever it is named. The members'
     * pictures as the core holds them (`AvatarsModel.state`, a map of user id to bytes) are the shape this catches.
     */
    @Test
    fun aByteArrayAnywhereOnTheWireIsRefusedHoweverItIsNamed() {
        assertEquals(
            listOf("Pictured.Shown.picture: bytes", "Pictured.Shown.pictures: bytes", "Pictured.Shown.frames: bytes"),
            leaks(listOf(Pictured.serializer()), identifiers),
        )
    }

    @Serializable @kotlinx.serialization.SerialName("Pictured")
    sealed class Pictured {
        @Serializable @kotlinx.serialization.SerialName("Pictured.Shown")
        class Shown(val name: String, val picture: ByteArray?, val pictures: Map<String, ByteArray>, val frames: List<ByteArray>) : Pictured()
    }

    @Serializable @kotlinx.serialization.SerialName("Leaky")
    class Leaky(val name: String, val sealKey: ByteArray)

    @Serializable @kotlinx.serialization.SerialName("Leaky2")
    class Leaky2(val refreshToken: String?)

    @Serializable @kotlinx.serialization.SerialName("Leaky3")
    sealed class Leaky3 {
        @Serializable @kotlinx.serialization.SerialName("Leaky3.Typed") class Typed(val password: String) : Leaky3()
    }

    private fun leaks(roots: List<KSerializer<*>>, allowed: Map<String, String>): List<String> {
        val schema = Schema(roots)
        return schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }.flatMap { f ->
            val at = "${f.owner}.${f.name}"
            listOfNotNull(
                "$at: named like a secret".takeIf { SECRET.containsMatchIn(f.name) && at !in allowed },
                "$at: bytes".takeIf { bytes(f.descriptor) },
            )
        }
    }

    /** Bytes: a list of bytes (`ByteArray`, `List<Byte>`), or a list or a map that holds one. */
    private fun bytes(d: SerialDescriptor): Boolean = when (d.kind) {
        StructureKind.LIST -> d.getElementDescriptor(0).let { it.kind == PrimitiveKind.BYTE || bytes(it) }
        StructureKind.MAP -> bytes(d.getElementDescriptor(0)) || bytes(d.getElementDescriptor(1))
        else -> false
    }

    companion object {
        private val SECRET = Regex("key|seal|token|secret|passw|private|wrap", RegexOption.IGNORE_CASE)
        private val ADDRESS = Regex("url|address", RegexOption.IGNORE_CASE)
        private val PAIRING = Regex("handle|payload|grant|envelope|channel|transport|marker", RegexOption.IGNORE_CASE)
    }
}
