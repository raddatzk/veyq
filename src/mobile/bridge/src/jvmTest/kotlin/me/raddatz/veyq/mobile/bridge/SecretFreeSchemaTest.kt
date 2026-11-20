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
        "PasswordChange.Run.currentPassword" to "the current password the person typed to change it, on its way to the core's cold proof",
        "PasswordChange.Run.newPassword" to "the new password the person typed, on its way to the core",
        "PasswordChange.Run.confirmPassword" to "the new password typed again, on its way to the core's check that both match",
        "PasswordChange.Run.secretKey" to "the Secret Key the person typed for the cold proof, on its way to the core",
        "AddressChange.Request.password" to "the password the person typed to change the address, on its way to the core's cold proof",
        "AddressChange.Request.secretKey" to "the Secret Key the person typed to change the address, on its way to the core's cold proof",
        "AddressChange.Kit.password" to "the password the person typed for a new kit, on its way to the core's cold proof",
        "AddressChange.Kit.secretKey" to "the Secret Key the person typed for a new kit, on its way to the core, which writes it into the file chosen",
        "KeyRotation.Prove.password" to "the password the person typed at the rotation's gate, on its way to the core's cold proof",
        "KeyRotation.Prove.secretKey" to "the Secret Key the person typed at the rotation's gate, on its way to the core's cold proof",
        "KeyRotation.Prove.newPassword" to "the new password the person chose to install with the new keys, on its way to the core",
        "KeyRotation.Prove.confirmPassword" to "the new password typed again, on its way to the core's check that both match",
        "PasswordChange.Check.newPassword" to "the new password as the person left its field, on its way to the core's breached-password check (only a 5-hex prefix of its SHA-1 leaves the device)",
        "KeyRotation.Check.newPassword" to "the rotation's new password as the person left its field, on its way to the core's breached-password check",
        "Received.Password.password" to "the password a received link asks for, as the person typed it, on its way to the core",
        "Bookmark.Create.password" to "a kept link's password as the person typed it into the bookmark dialog, on its way to the core, which seals it",
        "Erasure.Run.password" to "the password the person typed to erase the account, on its way to the core's proof",
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
        "me.raddatz.veyq.mobile.session.rotation.KeyRotation.State.newPassword" to "whether \"Also choose a new password\" is ticked: a flag, no password",
        "me.raddatz.veyq.mobile.session.ProfileModel.State.hasKey" to "whether this account holds the tenant key here (the name and picture editable): a flag, no key",
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
     * What the offline store adds to the wire crosses as sizes, times, states and the words to say them (ADR 0140 decision
     * 10, the CR's phases 3 and 5): a screen offline says as of when (`OfflineAsOf`: the shell's state, a gallery's), a
     * node's toggle where its fetch is (`OfflineToggle`), the Settings section how much is kept against the limit and the
     * pinned items (`AccountSettings.Storage`, `OfflineItem`) — never a key, a seal, an entry's opaque name or what it holds.
     * An item's [OfflineItem] `title` is its node's name from the tree the core holds (`TenantSession.trees`, as every
     * list's row is named), never from the store, which keeps no name: the store's own record of a pin carries ids alone
     * (`Pin` is not on the wire). A field added to any of these is red here until it is named below, with why; and each is
     * reached from the wire's roots.
     */
    @Test
    fun theOfflineStoreCrossesAsSizesTimesAndStatesAlone() {
        val schema = Schema(Roots.all)
        val fields = schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }.groupBy({ it.owner }, { it.name })
        fun of(suffix: String) = fields.entries.single { (owner, _) -> owner == suffix || owner.endsWith(".$suffix") }.value
        assertEquals(listOf("asOf"), of("OfflineAsOf"), "a screen offline says more than as of when")
        assertEquals(listOf("limit", "automatic", "pinned", "free", "field", "items"), of("AccountSettings.Storage"), "the Settings section's storage says more than sizes, its field and its items")
        assertEquals(listOf("amount", "unit", "off"), of("LimitField"))
        assertEquals(
            listOf("tenant", "scope", "node", "kind", "title", "status", "said", "bytes"), of("OfflineItem"),
            "a pinned item says more than its ids, its name from the held tree, its state and its size",
        )
        assertEquals(listOf("on", "status", "said", "done", "total", "asking", "question"), of("OfflineToggle"), "a node's toggle says more than its state and sizes")
        // a Pot offline (ADR 0153 decision 6): its state carries as of when beside what it carried online, never a revision
        assertEquals(listOf("ref", "view", "failed", "saving", "message", "offline"), of("PotModel.State"), "a Pot's state says more than its view, its flags and as of when")
        assertTrue(fields.keys.none { it in setOf("me.raddatz.veyq.mobile.offline.Pin", "me.raddatz.veyq.mobile.offline.PinRecord", "me.raddatz.veyq.mobile.offline.PinGone") }, "the store's own record of a pin is on the wire")
        // where they ride: the shell's state, a gallery's, a header's, the Settings page's
        val holders = fields.filterValues { names -> "offline" in names || "storage" in names }.keys.map { it.substringAfterLast('.') }.toSet()
        assertTrue(holders.containsAll(setOf("State", "GalleryShown", "SettingsShown", "HeaderShown")), "the offline states ride elsewhere: $holders")
    }

    /**
     * A map crosses as its picture alone (ADR 0143 decision 11, the CR's phase 5): the tiles in view by their `z/x/y` and
     * their place, the overlays as normalised points, the attribution and the hosts the consent names in fields of their
     * own — never a tile's address, the template, the geocoder's address, a bearer or a cookie (a tile is asked by its id,
     * `Map.Tile`, and its bytes ride beside the JSON). The map's outward shapes carry exactly these fields; one added is red
     * here until it is named below, with why — and a field named like an address or a secret is red in the checks above.
     */
    @Test
    fun aMapCrossesAsItsPictureAlone() {
        val schema = Schema(Roots.outward)
        val fields = schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }.groupBy({ it.owner }, { it.name })
        fun of(name: String) = fields[name] ?: fields.entries.single { (owner, _) -> owner.endsWith(".$name") }.value
        assertEquals(
            listOf("state", "view", "tiles", "attribution", "tracks", "dots", "clusters", "photo", "centrePin"), of("MapShown"),
            "a map says more than its state, its view, its tiles' places, its attribution and its overlays",
        )
        assertEquals(listOf("tile", "x", "y", "size"), of("TileShown"), "a tile says more than its z/x/y and its place")
        assertEquals(listOf("tileHost", "geocoderHost", "renewal"), of("MapState.Consent"), "the consent names more than two hosts")
        assertEquals(listOf("state", "consentTileHost", "consentGeocoderHost"), of("MapServicesShown"))
        assertEquals(listOf("lat", "lng", "x", "y", "zoom", "width", "height", "moved"), of("MapViewShown"))
        assertEquals(listOf("mediaId", "color", "dash", "selected", "segments"), of("TrackLineShown"), "a track's line says more than its points and its look")
        assertEquals(listOf("node", "page", "cover"), of("PinShown"))
        // the checks above against what they must refuse on a map
        assertEquals(listOf("Mapped.tileUrl: a link's address"), addresses(listOf(Mapped.serializer()), linkAddresses))
        assertEquals(listOf("Mapped.bearer: named like a secret", "Mapped.cookie: named like a secret"), leaks(listOf(Mapped.serializer()), identifiers))
    }

    /**
     * A Memory's curation and a person's page cross as ids, names and states alone (ADR 0144 decision 8, the CR's phase 5):
     * the participants as user ids and display names, a banner as its medium, its picture's cache key and a video's type
     * (its bytes asked by call, beside the JSON), the dialogs' states, a person's frame and their Memories as rows — never a
     * blob's ref or wrap, a slot's key or the file's digest (the banner's sealed `sha256`, which tells an equal gallery file),
     * and never the path a host picked a file from. No field anywhere on the wire is named like a digest; and these shapes
     * carry exactly these fields — one added is red here until it is named below, with why.
     */
    @Test
    fun aMemorysCurationAndAPersonsPageCrossAsIdsNamesAndStatesAlone() {
        assertEquals(emptyList(), digests(Roots.all))
        val schema = Schema(Roots.all)
        val fields = schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }.groupBy({ it.owner }, { it.name })
        fun of(name: String) = fields[name] ?: fields.entries.single { (owner, _) -> owner.endsWith(".$name") }.value
        assertEquals(listOf("medium", "picture", "mediaType"), of("BannerShown"), "a banner says more than its medium, its picture's key and a video's type")
        assertEquals(listOf("people", "avatars", "more", "named", "edits", "add"), of("PeopleShown"))
        assertEquals(listOf("userId", "name", "former", "opensProfile"), of("ParticipantShown"))
        assertEquals(
            listOf("members", "former", "failed", "query", "full", "noMatches", "nobody", "canSave", "saving", "said", "saved"), of("ParticipantsShown"),
        )
        assertEquals(listOf("userId", "name", "ticked", "togglable"), of("MemberShown"))
        assertEquals(listOf("atMs", "corrected", "originalMs", "saving", "said", "saved"), of("CaptureTimeShown"))
        assertEquals(listOf("hasBanner", "picking", "picked", "saving", "progress", "said", "done", "canSave", "canRemove"), of("BannerEditorShown"))
        assertEquals(listOf("medium", "mediaType", "pick"), of("BannerPickedShown"), "a picked file says more than its medium and type: its path echoed?")
        assertEquals(listOf("width", "height", "wide", "surroundWidth", "surroundHeight"), of("BannerFramed"))
        assertEquals(listOf("userId", "name", "former", "picture", "username", "sections", "memories"), of("PersonShown"))
        assertEquals(listOf("loading", "failed", "empty", "partial", "offersViews", "view", "rows", "located"), of("PersonMemoriesShown"))
        assertEquals(listOf("ref", "date", "place", "group", "groupName"), of("PersonRowShown"))
        // the check against what it must refuse: a banner's digest, by any of its names
        assertEquals(listOf("Digested.sha256", "Digested.originalDigest", "Digested.contentHash"), digests(listOf(Digested.serializer())))
    }

    /**
     * Memories' rest crosses as ids, counts, labels and states alone (ADR 0149 decision 10): a medium's badge as a count and
     * whether it is mine — never a mark set's ciphertext, its value key's wrap or its member's self tag —, the filter as its
     * mode, chips by identity and label and the text typed, the Contributions view as rows of ids and labels (a bucket's name
     * opened, never sealed). No field anywhere on the wire is named like a tag; and these shapes carry exactly these fields.
     */
    @Test
    fun theMarksTheFilterAndTheContributionsCrossAsIdsCountsAndLabelsAlone() {
        val schema = Schema(Roots.all)
        val fields = schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }
        assertEquals(emptyList(), fields.filter { TAG.containsMatchIn(it.name) }.map { "${it.owner}.${it.name}" })
        val byOwner = fields.groupBy({ it.owner }, { it.name })
        fun of(name: String) = byOwner[name] ?: byOwner.entries.single { (owner, _) -> owner.endsWith(".$name") }.value
        assertEquals(
            listOf("mediaId", "name", "mediaType", "video", "blocked", "tile", "picture", "capturedAt", "width", "height", "said", "canDelete", "marks", "mine"),
            of("GalleryRow"), "a gallery row says more than its medium's facts and its badge",
        )
        assertEquals(listOf("offered", "mode", "modeOffered", "chips", "text", "suggestions", "shown", "total"), of("FilterShown"))
        assertEquals(listOf("chip", "label", "rest"), of("ChipShown"))
        assertEquals(listOf("rows", "selected", "media", "createsSub", "renames", "moveTargets", "loading", "failed", "refused"), of("BucketsShown"))
        assertEquals(listOf("bucketId", "label", "depth", "mediaCount", "isPrimary", "own"), of("BucketRowShown"))
        assertEquals(listOf("x", "y", "size"), of("TileFrame"))
    }

    /**
     * The writes made offline cross as items and states alone (ADR 0153 decision 5, the CR's phase 6): a tray item says
     * what the write is by its kind's word and the name the person gave, where it goes, its state and line and what it
     * offers; *Open* is a typed target of ids and the person's name; the ask before a loss its words — never the write
     * itself (`QueuedWrite`: the person's fields as they set them, a note's text, an amount, the revision they saw), never a
     * queued blob (`QueuedBlob`: its `K_blob`, its frames), never an untyped JSON value (an intent carried through
     * uninterpreted). A *Save a copy*'s plaintext goes into the host's file by path, never on the wire. These shapes carry
     * exactly these fields; one added is red here until it is named below, with why.
     */
    @Test
    fun theWritesMadeOfflineCrossAsItemsAndStatesAlone() {
        assertEquals(emptyList(), queuedContent(Roots.all))
        val schema = Schema(Roots.all)
        val fields = schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }.groupBy({ it.owner }, { it.name })
        fun of(name: String) = fields[name] ?: fields.entries.single { (owner, _) -> owner.endsWith(".$name") }.value
        assertEquals(
            listOf("id", "op", "tenant", "scope", "node", "where", "title", "state", "said", "opens", "copies", "at"), of("QueuedItem"),
            "a write made offline says more than its kind's word, its name, where it goes, its state and what it offers",
        )
        assertEquals(listOf("tenant", "scope", "pot", "entry", "write"), of("QueuedOpen.Entry"))
        assertEquals(listOf("tenant", "scope", "node", "name"), of("QueuedOpen.Rename"))
        assertEquals(listOf("tenant", "scope", "node"), of("QueuedOpen.Place"))
        assertEquals(listOf("question", "confirm"), of("QueuedAsk"))
        assertEquals(listOf("name", "mediaType"), of("QueuedCopyName"), "a refused file's suggestion says more than its name and type")
        // the check against what it must refuse: the write itself, a queued blob, an intent as a JSON value
        assertEquals(
            listOf(
                "Intended.write: a queued write's content", "Intended.intent: a queued write's content",
                "Intended.blobs: a queued write's content", "me.raddatz.veyq.mobile.offline.QueuedWrite.intent: a queued write's content",
                "me.raddatz.veyq.mobile.offline.QueuedWrite.blobs: a queued write's content",
            ),
            queuedContent(listOf(Intended.serializer())),
        )
        assertTrue(leaks(listOf(Intended.serializer()), identifiers).any { it.endsWith("QueuedBlob.key: named like a secret") }, "a queued blob's K_blob crossed unseen")
    }

    @Serializable @kotlinx.serialization.SerialName("Intended")
    class Intended(
        val title: String,
        val write: me.raddatz.veyq.mobile.offline.QueuedWrite?,
        val intent: kotlinx.serialization.json.JsonObject?,
        val blobs: List<me.raddatz.veyq.mobile.offline.QueuedBlob>,
    )

    /** A field that carries a queued write, a queued blob or an untyped JSON value (an intent uninterpreted), anywhere. */
    private fun queuedContent(roots: List<KSerializer<*>>): List<String> {
        val schema = Schema(roots)
        fun carries(d: SerialDescriptor): Boolean = when (d.kind) {
            StructureKind.LIST, StructureKind.MAP -> d.serialName.removeSuffix("?").startsWith("kotlinx.serialization.json.") || (0 until d.elementsCount).any { carries(d.getElementDescriptor(it)) }
            else -> d.serialName.removeSuffix("?").let { n -> n.startsWith("kotlinx.serialization.json.") || n.endsWith("offline.QueuedWrite") || n.endsWith("offline.QueuedBlob") }
        }
        return schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }
            .filter { carries(it.descriptor) }.map { "${it.owner}.${it.name}: a queued write's content" }
    }

    @Serializable @kotlinx.serialization.SerialName("Digested")
    class Digested(val medium: String, val sha256: String?, val originalDigest: String, val contentHash: String)

    private fun digests(roots: List<KSerializer<*>>): List<String> {
        val schema = Schema(roots)
        return schema.types.values.flatMap { t -> schema.fields(t) }.distinctBy { it.owner to it.name }
            .filter { DIGEST.containsMatchIn(it.name) }.map { "${it.owner}.${it.name}" }
    }

    @Serializable @kotlinx.serialization.SerialName("Mapped")
    class Mapped(val tile: String, val tileUrl: String, val bearer: String?, val cookie: String?)

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
        assertEquals(listOf("Leaky4.K_blob: named like a secret", "Leaky4.kShare: named like a secret"), leaks(listOf(Leaky4.serializer()), identifiers))
    }

    @Serializable @kotlinx.serialization.SerialName("Leaky4")
    @Suppress("PropertyName")
    class Leaky4(val kind: String, val K_blob: String?, val kShare: String?)

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
                "$at: named like a secret".takeIf { (SECRET.containsMatchIn(f.name) || KEY_SYMBOL.containsMatchIn(f.name)) && at !in allowed },
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
        /** A mark set's self tag (`itemMarkSelfTag`) or any field named like one: what tells whose a set is. */
        private val TAG = Regex("tag", RegexOption.IGNORE_CASE)
        private val SECRET = Regex("key|seal|token|secret|passw|private|wrap|bearer|authori[sz]|cookie|credential", RegexOption.IGNORE_CASE)

        /**
         * A key named as the web and the ADRs name one (`K_blob`, `K_share`, `kNode`): a `K` or `k` followed by an underscore
         * or a capital, which [SECRET] does not see (a music track's `K_blob` on `MusicShown` crossed it unseen in a bite, ADR 0142's bridge, 2026-11-16).
         */
        private val KEY_SYMBOL = Regex("^[kK](_|[A-Z])")
        private val ADDRESS = Regex("url|address", RegexOption.IGNORE_CASE)
        /** A file's digest (a banner's sealed `sha256`, `blob-meta`'s): it tells which stored file is equal to which. */
        private val DIGEST = Regex("sha\\d|digest|hash|checksum", RegexOption.IGNORE_CASE)
        private val PAIRING = Regex("handle|payload|grant|envelope|channel|transport|marker", RegexOption.IGNORE_CASE)
    }
}
