package com.veyq.app.shared.appshell

import com.veyq.app.shared.blobstore.InMemoryBlobStore
import com.veyq.app.shared.blobstore.S3BlobStore
import com.veyq.app.shared.config.VeyqConfig.SigningMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

/**
 * What the shell serves, decided apart from the route that serves it. The request-level half — fallback,
 * headers, caching over a real classpath — is [AppShellSliceTest].
 *
 * **The per-mode cases are the claim "the policy cannot disagree with the signer", made testable** (ADR 0069
 * Decision 2). Each one compares the appended origin with the origin of a URL the **signer produced in that
 * mode**, never with a second reading of the config: a test that re-derived the expected origin from
 * `public-endpoint` would pass against a store that derived it the same wrong way.
 */
class AppShellTest {

    @Test
    fun inStoreOriginModeThePolicyAllowsTheOriginTheSignerSignsFor() {
        // ADR 0064's Kubernetes shape: the browser is sent to an Ingress host the backend never uses itself.
        val store = s3Store(SigningMode.STORE_ORIGIN, publicEndpoint = "https://store.example.net:8443")
        val signed = originOf(store.presignGet(KEY, "tenant.example.com"))

        assertEquals("https://store.example.net:8443", signed, "control: the signer's own answer in this mode")
        assertEquals(originOf(store.presignPut(KEY, 1L, null)), signed, "PUT and GET must name one origin")
        assertEquals("$POLICY_WITHOUT_STORE $signed; form-action 'self'", cspOf(store.storeOrigin))
    }

    @Test
    fun inInternalEndpointModeThePolicyAllowsTheBackendsOwnEndpoint() {
        // A public endpoint IS configured and is deliberately not what gets signed — so an origin read off
        // `public-endpoint` fails here, which a blank one would have hidden.
        val store = s3Store(
            SigningMode.INTERNAL_ENDPOINT,
            internalEndpoint = "https://s3.eu-central-1.amazonaws.com",
            publicEndpoint = "https://ignored.example.net",
        )
        val signed = originOf(store.presignPut(KEY, 1L, "tenant.example.com"))

        assertEquals("https://s3.eu-central-1.amazonaws.com", signed)
        assertEquals("$POLICY_WITHOUT_STORE $signed; form-action 'self'", cspOf(store.storeOrigin))
    }

    @Test
    fun inTenantDomainModeNothingIsAppendedBecauseTheSignedOriginIsThePagesOwn() {
        // The compose shape: the URL is signed for the tenant's own domain, which is the origin the page is on,
        // so `'self'` covers it. The positive half is asserted too — a store that appended nothing because it
        // signed for nowhere would pass the equality below on its own.
        val store = s3Store(SigningMode.TENANT_DOMAIN, publicEndpoint = "https://veyq.example.org")

        assertEquals("https://tenant.example.com", originOf(store.presignGet(KEY, "tenant.example.com")))
        assertNull(store.storeOrigin)
        assertEquals(POLICY, cspOf(store.storeOrigin))
    }

    @Test
    fun theInMemoryStoreAppendsNothing() {
        assertNull(InMemoryBlobStore().storeOrigin)
        assertEquals(POLICY, cspOf(InMemoryBlobStore().storeOrigin))
    }

    @Test
    fun noIndexHtmlIsNoShellWhateverElseIsThere() {
        // Dev and the CI e2e jar: the backend serves the API only. The policy file alone does not make a shell.
        assertSame(AppShell.Absent, AppShell.from(indexHtml = null, policy = POLICY, storeOrigin = null))
    }

    @Test
    fun aShellWithNoEmittedPolicyRefusesToStart() {
        // ADR 0069 Decision 3: the CSP is the backstop of an operator-blind client, so a shell without one fails
        // loudly instead of booting quietly. Blank counts as missing — an empty header is no policy either.
        for (policy in listOf(null, "", " \n")) {
            val refused = assertThrows<IllegalStateException>("policy=${policy?.let { "\"$it\"" }}") {
                AppShell.from(INDEX, policy = policy, storeOrigin = STORE)
            }
            assertTrue(refused.message!!.contains(AppShell.POLICY_FILE), refused.message)
        }
        // Control: the same index with a policy boots, so the refusal above is about the policy.
        assertEquals(
            "$POLICY_WITHOUT_STORE $STORE; form-action 'self'",
            (AppShell.from(INDEX, policy = POLICY, storeOrigin = STORE) as AppShell.Present).csp,
        )
    }

    @Test
    fun aShellWhoseIndexStillCarriesACspMetaRefusesToStart() {
        // A returning `<meta>` intersects with the header and blocks every store fetch on Kubernetes again, and
        // no CI run would notice — so the build's regression becomes a boot failure. Spelled the ways an HTML
        // attribute may be: another case, single quotes, no quotes, other attributes first.
        for (meta in listOf(
            """<meta http-equiv="Content-Security-Policy" content="default-src 'self'">""",
            """<META HTTP-EQUIV='content-security-policy' CONTENT="default-src 'self'">""",
            """<meta content="default-src 'self'" http-equiv=Content-Security-Policy>""",
        )) {
            val index = "<!doctype html><html><head>$meta</head></html>".toByteArray()
            assertThrows<IllegalStateException>(meta) { AppShell.from(index, policy = POLICY, storeOrigin = null) }
        }
        // Control: a head with other metas — the ones the real index.html carries — boots.
        val ordinary = """<!doctype html><html><head><meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" /></head></html>""".toByteArray()
        assertEquals(POLICY, (AppShell.from(ordinary, policy = POLICY, storeOrigin = null) as AppShell.Present).csp)
    }

    @Test
    fun aPolicyWithoutConnectSrcIsRefusedRatherThanGivenOne() {
        // Writing a `connect-src` from scratch would replace its `default-src` fallback, not extend it. The
        // control: with no origin to append, the same policy passes through untouched.
        val policy = "default-src 'self'; script-src 'self'"
        assertThrows<IllegalStateException> { AppShell.withStoreOrigin(policy, STORE) }
        assertEquals(policy, AppShell.withStoreOrigin(policy, null))
    }

    @Test
    fun theEmittedFilesFormattingIsNotPartOfThePolicy() {
        // The file ends in a newline and may carry a trailing `;`; neither may reach the header as an empty
        // directive or a stray separator.
        assertEquals(POLICY, AppShell.withStoreOrigin("  $POLICY; \n", null))
    }

    private fun cspOf(storeOrigin: String?): String =
        (AppShell.from(INDEX, POLICY, storeOrigin) as AppShell.Present).csp

    private fun originOf(url: String): String =
        URI.create(url).let { "${it.scheme}://${it.host}${if (it.port == -1) "" else ":${it.port}"}" }

    private fun s3Store(
        mode: SigningMode,
        internalEndpoint: String = "http://garage.veyq.svc.cluster.local:3900",
        publicEndpoint: String,
    ) = S3BlobStore(
        s3 = NoS3Client, bucket = "veyq", presignTtlSeconds = 300,
        internalEndpoint = internalEndpoint, publicEndpoint = publicEndpoint, signingMode = mode,
        credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret")),
        region = "us-east-1",
    )

    private companion object {
        const val KEY = "storage-key/0"
        const val STORE = "https://store.example.net"
        val INDEX = "<!doctype html>".toByteArray()
        const val POLICY_WITHOUT_STORE =
            "default-src 'self'; frame-ancestors 'none'; connect-src 'self' https://api.pwnedpasswords.com"
        const val POLICY = "$POLICY_WITHOUT_STORE; form-action 'self'"

        /** Presigning is local HMAC and never connects (ADR 0013 §4); nothing here calls `stat` or `delete`. */
        val NoS3Client = object : S3Client {
            override fun serviceName() = "s3"
            override fun close() = Unit
        }
    }
}
