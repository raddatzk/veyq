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
 * No key, seal, token or secret crosses the bridge (the CR's invariant): no generated type carries a field named like
 * one, and none carries bytes — bytes the UI shows travel beside the JSON, never in it. The check walks what the
 * generator walks ([Schema]), so a type added to the wire is checked without a list of its own.
 *
 * Two kinds of name are let through, each by its owner and field, each with why:
 * - inward, what the person typed on its way to the core — the sign-in's password and Secret Key, which cross once and
 *   never come back;
 * - an identifier or a catalog key that only shares the word: a connection's key (`host#userId`, it names an account),
 *   a bar place's key (the catalog's name of its words) and a trusted device's wrap id (the server's name for the wrap).
 */
class SecretFreeSchemaTest {
    private val typedIn = mapOf(
        "Session.SignIn.password" to "the password the person typed, on its way to the core",
        "Session.SignIn.secretKey" to "the Secret Key the person typed, on its way to the core",
    )
    private val identifiers = mapOf(
        "Signed.connectionKey" to "a connection's id, host#userId: it names an account and opens nothing",
        "me.raddatz.veyq.mobile.navigation.BarTab.key" to "the catalog's name of a bar place's words",
        "me.raddatz.veyq.mobile.session.TrustedDevice.wrapId" to "the id the server gave this device's wrap: it names the wrap, holds none of it",
    )

    @Test
    fun nothingTheCoreSendsCarriesAKeyASealATokenASecretOrBytes() {
        assertEquals(emptyList(), leaks(Roots.outward, identifiers))
    }

    @Test
    fun whatTheHostSendsCarriesNoneButWhatThePersonTyped() {
        assertEquals(emptyList(), leaks(Roots.inward, identifiers + typedIn))
        // and each exception still names a field the wire has: one that is gone leaves no hole to fill unseen
        val fields = Schema(Roots.all).let { s -> s.types.values.flatMap { t -> s.fields(t).map { "${it.owner}.${it.name}" } } }.toSet()
        assertEquals(emptySet(), (typedIn + identifiers).keys - fields)
    }

    /** The check itself, against the state the CR names: a field `sealKey: ByteArray` is red twice — its name and its bytes. */
    @Test
    fun aStateThatCarriesASealKeyIsRefused() {
        assertEquals(
            listOf("Leaky.sealKey: named like a secret", "Leaky.sealKey: bytes"),
            leaks(listOf(Leaky.serializer()), identifiers),
        )
        assertEquals(listOf("Leaky2.refreshToken: named like a secret"), leaks(listOf(Leaky2.serializer()), identifiers))
        assertTrue(leaks(listOf(Leaky3.serializer()), identifiers).single().endsWith("password: named like a secret"))
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

    private fun bytes(d: SerialDescriptor): Boolean =
        d.kind == StructureKind.LIST && d.getElementDescriptor(0).kind.let { it == PrimitiveKind.BYTE }

    companion object {
        private val SECRET = Regex("key|seal|token|secret|passw|private|wrap", RegexOption.IGNORE_CASE)
    }
}
