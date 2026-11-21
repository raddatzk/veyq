package me.raddatz.veyq.mobile.words

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A value as the person reads it, written on the wire in the language of the encoder's [WireWords.module] ([Shown]'s
 * text, ADR 0154 decision 5): a wire class carries one beside the raw value it keeps for its own use, so a host across
 * the bridge draws a string and formats nothing. On the wire, and in the hosts' generated bindings, it is a string;
 * read back (a test reading the wire it wrote) it is that string.
 */
@Serializable(with = Written.AsString::class)
class Written private constructor(private val write: (String) -> String) {
    /** The text in [language]. */
    fun inLanguage(language: String): String = write(language)

    override fun toString() = "Written(${write("en")})"

    companion object {
        /** A count of bytes ([Shown.size]). */
        fun size(bytes: Long) = Written { Shown.size(bytes, it) }

        /** An instant in epoch ms as a date and time ([Shown.moment]). */
        fun moment(epochMs: Long) = Written { Shown.moment(epochMs, it) }

        /** A server's instant (ISO 8601) as a date and time ([Shown.moment]). */
        fun moment(instant: String) = Written { Shown.moment(instant, it) }

        /** A server's instant (ISO 8601) as its local day ([Shown.dayOf]). */
        fun dayOf(instant: String) = Written { Shown.dayOf(instant, it) }

        /** An ISO day (`2026-11-20`) as [Shown.day] writes it. */
        fun day(iso: String) = Written { Shown.day(iso, it) }

        /** A Memory's ISO day, numeric ([Shown.memoryDay]). */
        fun memoryDay(iso: String) = Written { Shown.memoryDay(iso, it) }

        /** A Memory's closed range of days ([Shown.dayRange]). */
        fun dayRange(from: String, to: String?) = Written { Shown.dayRange(from, to, it) }

        /** A track's distance, metres or kilometres as the core rounded it ([Shown.distance]). */
        fun distance(value: Double, kilometres: Boolean) = Written { Shown.distance(value, kilometres, it) }

        /** A track's length or a player's clock in ms ([Shown.length]). */
        fun length(ms: Long) = Written { Shown.length(ms) }
    }

    /** A [Written] as its text in the encoder's language; read back, that text in every language. */
    object AsString : KSerializer<Written> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("me.raddatz.veyq.mobile.words.Written", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: Written) = encoder.encodeString(value.inLanguage(WireWords.languageOf(encoder)))
        override fun deserialize(decoder: Decoder): Written = decoder.decodeString().let { text -> Written { text } }
    }
}
