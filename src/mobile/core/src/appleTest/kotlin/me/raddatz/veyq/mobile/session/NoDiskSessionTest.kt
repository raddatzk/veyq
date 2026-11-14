package me.raddatz.veyq.mobile.session

import platform.Foundation.NSHTTPCookieAcceptPolicy
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The Darwin session keeps no response cache, no cookie and no credential (`keepNothingOnDisk`). */
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
}
