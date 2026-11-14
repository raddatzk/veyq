package me.raddatz.veyq.mobile.session

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHTTPCookieAcceptPolicy
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.valueForKey
import platform.objc.sel_registerName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Darwin session keeps no response cache, no cookie and no credential (`keepNothingOnDisk`), and runs on an
 * ephemeral configuration, whose storage session (the `Alt-Svc` and HSTS records CFNetwork otherwise keeps in
 * `Library/HTTPStorages`) lives in memory only (`ephemeralNoDiskConfiguration`).
 */
class NoDiskSessionTest {
    @Test
    fun theDefaultConfigurationWouldKeepWhatTheAppDoesNot() {
        // The premise: what Ktor's Darwin engine starts from keeps a cache and cookies, else the test below proves nothing.
        val plain = NSURLSessionConfiguration.defaultSessionConfiguration
        assertNotNull(plain.URLCache)
        assertNotNull(plain.HTTPCookieStorage)
    }

    @Test
    fun keepNothingOnDiskClearsCacheCookiesAndCredentials() {
        val c = NSURLSessionConfiguration.defaultSessionConfiguration.apply { keepNothingOnDisk() }
        assertNull(c.URLCache)
        assertEquals(NSURLRequestReloadIgnoringLocalCacheData, c.requestCachePolicy)
        assertNull(c.HTTPCookieStorage)
        assertFalse(c.HTTPShouldSetCookies)
        assertEquals(NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever, c.HTTPCookieAcceptPolicy)
        assertNull(c.URLCredentialStorage)
    }

    @Test
    fun theCoresSessionsRunOnAnEphemeralConfigurationThatKeepsNothing() {
        val c = ephemeralNoDiskConfiguration()
        assertEquals("EphemeralDisposition", disposition(c), "the core's sessions are not ephemeral: CFNetwork keeps its storage session on disk")
        assertNull(c.URLCache)
        assertEquals(NSURLRequestReloadIgnoringLocalCacheData, c.requestCachePolicy)
        assertNull(c.HTTPCookieStorage)
        assertFalse(c.HTTPShouldSetCookies)
        assertEquals(NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever, c.HTTPCookieAcceptPolicy)
        assertNull(c.URLCredentialStorage)
        // the premise: clearing the stores leaves a default configuration a default one, else the check above proves nothing
        assertEquals("DefaultDisposition", disposition(NSURLSessionConfiguration.defaultSessionConfiguration.apply { keepNothingOnDisk() }))
    }

    /**
     * The configuration's kind, which no public API names: Foundation's own `disposition` (`DefaultDisposition`,
     * `EphemeralDisposition`; measured 2026-11-13 on macOS 26). Asked only where the configuration answers it, so a
     * Foundation without it fails here instead of raising.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun disposition(c: NSURLSessionConfiguration): String {
        assertTrue(c.respondsToSelector(sel_registerName("disposition")), "Foundation no longer names a configuration's disposition")
        return c.valueForKey("disposition").toString()
    }
}
