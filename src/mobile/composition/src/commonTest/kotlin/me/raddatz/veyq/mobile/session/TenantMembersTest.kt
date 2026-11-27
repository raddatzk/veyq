package me.raddatz.veyq.mobile.session

import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings' *Members* and the owner's terms editor over the generated client (uc.spaces.remove-tenant-member,
 * uc.admin.tenant-terms; `scopes.openapi.yaml` `listScopeMembers`, `listMyTenants`; `tenants.openapi.yaml`
 * `setTenantMemberRole`, `removeTenantMember`, `updateTenantTerms`), against a server the test plays: who sees which act,
 * what each request carries and how each refusal is said. It names no application: the account opens over the whole
 * composition.
 */
class TenantMembersTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private var tenantId = ""
    private var myRole = "OWNER"
    private val requests = mutableListOf<Triple<String, String, String>>()

    private suspend fun tenant(route: (r: HttpRequestData, path: String, method: String, body: String) -> Pair<Int, String>?): TenantSession {
        val t = PlayedTenant()
        // the tenant's own scope, whose id is the tenant's: the roster opens names under its key
        t.tenants[0] = PlayedTenant.Tenant(t.id("space"), "a.example")
        t.scope("space", kind = "TENANT")
        t.route = { r ->
            val body = (r.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            val path = r.url.encodedPath
            requests += Triple(r.method.value, path + (r.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""), body)
            route(r, path, r.method.value, body) ?: if (path == "/api/scopes/my-tenants" && tenantId.isNotEmpty()) {
                200 to """[{"tenantId":"$tenantId","name":"T","domain":"a.example","role":"$myRole","status":"ACTIVE","iconEpoch":0}]"""
            } else null
        }
        t.remember()
        val account = t.resume()
        return account.open(account.tenants.first()).also { tenantId = it.tenant.tenantId }
    }

    private fun member(id: String, name: String, role: String, email: String? = null, pending: Boolean = false) =
        """{"userId":"$id","username":"$name","avatarRevision":0,"role":"$role","identityPublicKey":"AAAA","keyChainHead":"AAAA","pending":$pending${email?.let { ""","email":"$it"""" } ?: ""}}"""

    private val roster = "[" + listOf(member("u-a", "anna", "OWNER", "anna@a.example"), member("u-b", "ben", "MEMBER", pending = true)).joinToString(",") + "]"

    private fun served(path: String) = path.startsWith("/api/scopes/") && path.endsWith("/members")

    @Test
    fun an_owner_is_offered_the_webs_acts_on_each_row_and_a_member_none() = runTest {
        val ts = tenant { _, path, method, _ -> if (method == "GET" && served(path)) 200 to roster else null }
        val read = ts.members.read()
        assertTrue(read.owner)
        assertNull(read.said)
        val (anna, ben) = read.rows
        assertEquals(listOf(false, true, false), listOf(anna.promote, anna.demote, anna.remove), "an owner's row: Remove co-owner only")
        assertEquals(listOf(true, false, true), listOf(ben.promote, ben.demote, ben.remove), "a member's row: Make co-owner and Remove")
        assertEquals("@anna", anna.title, "no real name opens: the title is the username")
        assertNull(anna.usernameLine)
        assertEquals("anna@a.example", anna.email)
        assertEquals(Msg.MEMBERS_ROLE_OWNER, anna.roleWord)
        assertEquals(Msg.MEMBERS_ROLE_MEMBER, ben.roleWord)
        assertEquals(Msg.MEMBERS_PENDING, ben.pendingWord)
        assertNull(anna.pendingWord)

        myRole = "MEMBER"
        val asMember = ts.members.read()
        assertFalse(asMember.owner)
        assertTrue(asMember.rows.none { it.promote || it.demote || it.remove }, "a member is offered no act")
    }

    @Test
    fun a_list_that_does_not_read_is_said_with_try_again_and_an_empty_one_is_said() = runTest {
        var status = 500
        val ts = tenant { _, path, method, _ -> if (method == "GET" && served(path)) status to (if (status == 200) "[]" else "{}") else null }
        assertEquals(TenantMembers.Roster(emptyList(), owner = false, said = Msg.MEMBERS_LOAD_FAILED, retry = true), ts.members.read())
        status = 200
        assertEquals(Msg.MEMBERS_EMPTY, ts.members.read().said)
    }

    @Test
    fun a_role_change_names_the_role_and_says_the_last_owner_apart() = runTest {
        var status = 204
        val ts = tenant { _, path, method, _ ->
            if (method == "PATCH" && path.contains("/members/u-")) status to (if (status == 409) """{"code":"last_owner"}""" else "") else null
        }
        val up = ts.members.setRole("u-b", owner = true)
        assertTrue(up.done)
        assertEquals(Notice(Msg.MEMBERS_PROMOTED), up.notice)
        assertTrue(requests.last().third.contains("\"OWNER\""), "the request names the role")
        assertTrue(requests.last().second.startsWith("/api/tenants/$tenantId/members/u-b"))
        assertEquals(Notice(Msg.MEMBERS_DEMOTED), ts.members.setRole("u-b", owner = false).notice)
        assertTrue(requests.last().third.contains("\"MEMBER\""))
        status = 409
        val last = ts.members.setRole("u-a", owner = false)
        assertFalse(last.done)
        assertEquals(Notice(Msg.MEMBERS_LAST_OWNER), last.notice)
        status = 500
        assertEquals(Notice(Msg.MEMBERS_ROLE_CHANGE_FAILED), ts.members.setRole("u-a", owner = false).notice)
    }

    @Test
    fun a_removal_sends_revoke_links_only_when_ticked() = runTest {
        var status = 204
        val ts = tenant { _, path, method, _ -> if (method == "DELETE" && path.endsWith("/members/u-b")) status to "" else null }
        assertEquals(Notice(Msg.MEMBERS_REMOVED), ts.members.remove("u-b", revokeLinks = false).notice)
        val plain = requests.last { it.first == "DELETE" }.second
        assertFalse(plain.contains("revokeLinks"), "unticked, no parameter is sent: $plain")
        ts.members.remove("u-b", revokeLinks = true)
        assertTrue(requests.last { it.first == "DELETE" }.second.contains("revokeLinks=true"))
        status = 404
        val failed = ts.members.remove("u-b", revokeLinks = false)
        assertFalse(failed.done)
        assertEquals(Notice(Msg.MEMBERS_ACTION_FAILED), failed.notice)
    }

    private val terms = """{"termsRevision":3,"accepted":true,"terms":{"de":"## Regeln","en":""},
        "operatorSection":{"revision":2,"text":{"en":"## On this server"},"record":{}}}"""

    @Test
    fun the_editor_is_the_owners_and_a_save_sends_the_languages_the_draft_carries() = runTest {
        var status = 200
        val ts = tenant { _, path, method, _ ->
            when {
                method == "GET" && path.endsWith("/terms") -> 200 to terms
                method == "PUT" && path.endsWith("/terms") ->
                    status to (if (status == 200) """{"termsRevision":4,"accepted":false,"terms":{"en":"## Rules"},"operatorSection":{"revision":2,"text":{},"record":{}}}""" else "{}")
                else -> null
            }
        }
        val editing = MemberTerms.editing(ts, "en")!!
        assertTrue(editing.owner)
        assertEquals(listOf("en", "de"), editing.draft.texts.map { it.lang }, "an empty language present is kept, in the web's order")
        assertEquals("English", editing.draft.blankNames)
        myRole = "MEMBER"
        assertFalse(MemberTerms.editing(ts, "en")!!.owner)

        val draft = editing.draft.without("de").withText("en", "## Rules")
        val saved = MemberTerms.save(ts, draft)
        assertEquals(Notice(Msg.TERMS_SAVED, 4), saved.notice)
        assertEquals(listOf(MemberTerms.LangText("en", "## Rules")), saved.draft!!.texts, "the editor re-draws from the server's answer")
        val body = requests.last { it.first == "PUT" }.third
        assertTrue(body.contains("\"en\":\"## Rules\"") && !body.contains("\"de\""), "a removed language is sent absent: $body")
        status = 403
        assertEquals(Notice(Msg.TERMS_NOT_OWNER), MemberTerms.save(ts, draft).notice)
        status = 500
        val failed = MemberTerms.save(ts, draft)
        assertNull(failed.draft)
        assertEquals(Notice(Msg.TERMS_SAVE_FAILED), failed.notice)
    }
}
