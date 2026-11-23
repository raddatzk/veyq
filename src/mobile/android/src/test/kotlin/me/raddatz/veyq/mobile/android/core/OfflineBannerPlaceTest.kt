package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the shell's offline banner stands (inbox #961): under the top app bar on a page, and under the search's field while
 * searching — Material 3's full-screen search takes the screen, so the banner never draws above it. The shell's own frame
 * ([ShellTopBar] over [SearchPage]) with stand-ins for the bar, the field and the banner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OfflineBannerPlaceTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Banner() = Box(Modifier.fillMaxWidth().height(40.dp).testTag("banner"))

    @Composable
    private fun Shell(searching: Boolean) {
        Scaffold(topBar = {
            ShellTopBar(searching, banner = { Banner() }, appBar = { Box(Modifier.fillMaxWidth().height(64.dp).testTag("app-bar")) }) {}
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (searching) {
                    SearchPage(
                        inputField = { Box(Modifier.fillMaxWidth().height(56.dp).testTag("search-field")) },
                        expanded = true,
                        onExpandedChange = {},
                        banner = { Banner() },
                    ) { Box(Modifier.fillMaxWidth().height(80.dp).testTag("search-results")) }
                } else {
                    Box(Modifier.fillMaxSize().testTag("page"))
                }
            }
        }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.top
    private fun bottom(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.bottom

    @Test
    fun searchingTheBannerStandsUnderTheFieldAboveTheResults() {
        compose.setContent { Shell(searching = true) }
        compose.onAllNodesWithTag("banner", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("app-bar", useUnmergedTree = true).assertCountEquals(0)
        assertTrue("the banner (top ${top("banner")}) draws under the search field (bottom ${bottom("search-field")})", top("banner") >= bottom("search-field"))
        assertTrue("the results (top ${top("search-results")}) follow the banner (bottom ${bottom("banner")})", top("search-results") >= bottom("banner"))
    }

    @Test
    fun onAPageTheBannerStandsUnderTheTopAppBar() {
        compose.setContent { Shell(searching = false) }
        compose.onAllNodesWithTag("banner", useUnmergedTree = true).assertCountEquals(1)
        assertTrue(top("banner") >= bottom("app-bar"))
        assertTrue(top("page") >= bottom("banner"))
    }
}
