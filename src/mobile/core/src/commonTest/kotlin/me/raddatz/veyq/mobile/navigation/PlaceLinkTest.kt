package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.sharing.DeepLink
import me.raddatz.veyq.mobile.sharing.OpenLink
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A place reached from outside the app (inbox #1415): the allow-list a `veyq://open` link and a member's web URL pass. Each
 * refused input differs from an accepted control in one part, so a refusal names the check that made it.
 * Bites (each made red once, then restored): `ofDeepLink` without its key-set check (the `k` and `x` cases), without its id
 * check (a name as `s`); `ofWeb` without its path check (`/s/…`, `/tenant/accept`, `/recover`), without its query check, and
 * the route without its slug check (`#k=…`).
 */
class PlaceLinkTest {
    private val scope = "0b9e2a52-6a3e-4f3c-9d55-0d1f7e6f2a10"
    private val node = "7c1d8e4f-2b3a-4c5d-8e9f-0a1b2c3d4e5f"
    private val i = "i=https%3A%2F%2Fphotos.example.org"

    private fun ids(uri: String): PlaceLink.Target.Ids? = PlaceLink.ofDeepLink(uri)?.target as? PlaceLink.Target.Ids

    @Test
    fun a_veyq_link_carries_an_origin_and_ids_or_a_settings_section() {
        val link = assertNotNull(PlaceLink.ofDeepLink("veyq://open?$i&s=$scope&n=$node"))
        assertEquals("https://photos.example.org", link.origin)
        val t = assertIs<PlaceLink.Target.Ids>(link.target)
        assertEquals(scope to node, t.scopeId to t.nodeId)
        assertNull(assertNotNull(ids("veyq://open?$i&s=$scope")).nodeId)
        assertEquals(scope, ids("veyq://open?$i&s=${scope.uppercase()}")?.scopeId, "an id is read in either case")
        assertEquals(PlaceLink.Target.Settings::class, PlaceLink.ofDeepLink("veyq://open?$i&settings=tenant-members")?.target?.let { it::class })
        assertEquals("http://veyq.localhost:8081", PlaceLink.ofDeepLink("veyq://open?i=http%3A%2F%2Fveyq.localhost%3A8081&s=$scope")?.origin)
    }

    @Test
    fun a_veyq_link_with_anything_else_is_no_place() {
        for (uri in listOf(
            "veyq://open?$i&s=$scope&k=S2V5",          // a key beside it
            "veyq://open?$i&s=$scope&x=CANARY",        // any other parameter
            "veyq://open?$i&s=$scope#k=S2V5",          // a fragment
            "veyq://open?$i&s=$scope&s=$scope",        // a repeated one
            "veyq://open?$i&s=reisen-2026",            // a name, not an id
            "veyq://open?$i&s=$scope&n=rom",
            "veyq://open?s=$scope",                    // no origin
            "veyq://open?i=https%3A%2F%2Fu%3Apw%40photos.example.org&s=$scope", // credentials in the origin
            "veyq://open?i=https%3A%2F%2Fphotos.example.org%2Fs%2Fx&s=$scope",  // a path in the origin
            "veyq://open?i=ftp%3A%2F%2Fphotos.example.org&s=$scope",
            "veyq://open?i=%ZZ&s=$scope",
            "veyq://open?$i&settings=erase",           // a section that is one action
            "veyq://open?$i&settings=sign-out",
            "veyq://open?$i&settings=nope",
            "veyq://open?$i&settings=tenant-members&s=$scope",
            "veyq://link?$i&s=$scope",                 // not open
            "https://photos.example.org/?$i&s=$scope",
        )) assertNull(PlaceLink.ofDeepLink(uri), uri)
    }

    @Test
    fun the_deep_link_answers_at_only_for_a_place_and_names_nothing_of_it() {
        val at = assertIs<DeepLink.At>(DeepLink.parse("veyq://open?$i&s=$scope&n=$node"))
        assertEquals("At", at.toString())
        assertEquals("PlaceLink", at.place.toString())
        assertFalse(at.toString().contains(scope) || at.place.toString().contains("photos"))
        assertEquals(DeepLink.Open, DeepLink.parse("veyq://open?$i&s=$scope&x=CANARY"))
        assertEquals(DeepLink.Open, DeepLink.parse("veyq://open"))
        assertEquals(DeepLink.Link, DeepLink.parse("veyq://link?$i&s=$scope"))
    }

    private fun route(url: String): PlaceLink.Target.Route? = PlaceLink.ofWeb(url)?.target as? PlaceLink.Target.Route

    @Test
    fun a_members_web_link_is_read_in_the_webs_route_form() {
        val r = assertNotNull(route("https://photos.example.org/#~me/reisen-2026/memories/rom"))
        assertEquals(Triple("~me", listOf("reisen-2026", "memories", "rom"), false), Triple(r.scope, r.segments, r.folder))
        assertEquals("https://photos.example.org", PlaceLink.ofWeb("https://photos.example.org/#~me/reisen-2026/memories/rom")?.origin)
        assertEquals(true, route("https://photos.example.org/#reisen-2026/")?.folder)
        assertEquals("", route("https://photos.example.org/#reisen-2026/")?.scope, "token-less is the tenant's")
        assertEquals("@familie", route("https://photos.example.org/#@familie/sommer")?.scope)
        assertEquals(emptyList(), route("https://photos.example.org/")?.segments)
        assertEquals(emptyList(), route("https://photos.example.org")?.segments)
        assertIs<PlaceLink.Target.Settings>(PlaceLink.ofWeb("https://photos.example.org/settings/tenant-members")?.target)
        assertNull((PlaceLink.ofWeb("https://photos.example.org/settings")?.target as PlaceLink.Target.Settings).section)
        assertNotNull(PlaceLink.ofWeb("http://veyq.localhost:8081/#~me"))
    }

    @Test
    fun a_web_link_that_is_no_view_of_the_allow_list_is_no_place() {
        for (url in listOf(
            "https://photos.example.org/s/Sh4reId#k=S2V5",             // a share link keeps its own path
            "https://photos.example.org/tenant/accept#secret",          // an invite
            "https://photos.example.org/recover#token",                 // recovery
            "https://photos.example.org/confirm-email#token",
            "https://photos.example.org/confirm-email-change#token",
            "https://photos.example.org/moderation",
            "https://photos.example.org/invitations",
            "https://photos.example.org/terms",
            "https://photos.example.org/login",
            "https://photos.example.org/?x=1#reisen",                  // a query
            "https://photos.example.org/#k=S2V5",                      // a key in the fragment
            "https://photos.example.org/#reisen/Rom",                  // no slug
            "https://photos.example.org/#people/anna",                 // a person's page
            "https://photos.example.org/#~anna/reisen",                // no scope token
            "https://photos.example.org/#@Familie",
            "https://photos.example.org/settings/erase",
            "https://photos.example.org/settings/sign-out",
            "https://photos.example.org/settings/tenant-members#x",
            "https://u:pw@photos.example.org/#reisen",                 // credentials
            "ftp://photos.example.org/#reisen",
            "Look at https://photos.example.org/#reisen",
        )) assertNull(PlaceLink.ofWeb(url), url)
    }

    @Test
    fun open_a_link_hands_a_members_link_to_the_shell_and_says_where_it_is_not_open() {
        val field = OpenLink { _, _ -> error("no guest view for a member's link") }
        val url = "https://photos.example.org/#~me/reisen-2026/"
        field.text(url)
        assertTrue(field.state.value.canOpen && field.state.value.place)
        assertNull(field.state.value.problem)
        // no shell open (the sign-in): said in the field, the text kept
        assertEquals(Arrived.NotHere, field.openPlace { null })
        assertEquals(Msg.LINK_PROBLEM_NOT_OPEN_HERE, field.state.value.problem)
        assertEquals(url, field.state.value.text)
        field.text(url)
        var handed: PlaceLink? = null
        assertEquals(Arrived.Opened, field.openPlace { handed = it; Arrived.Opened })
        assertNotNull(handed)
        assertEquals(OpenLink.State(), field.state.value, "the field keeps nothing once opened")
        assertNull(field.openPlace { Arrived.Opened }, "handed once")
        // the unlock on screen: held for the shell it opens, the field emptied as for an opened one
        field.text(url)
        assertEquals(Arrived.Waiting, field.openPlace { Arrived.Waiting })
        assertEquals(OpenLink.State(), field.state.value)
        // a share link is still a guest's, and a foreign link still refused as before
        field.text("https://photos.example.org/s/Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_2#k=S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2U")
        assertFalse(field.state.value.place)
        field.text("https://photos.example.org/recover#token")
        assertEquals(Msg.LINK_PROBLEM_NOT_A_SHARE, field.state.value.problem)
    }
}
