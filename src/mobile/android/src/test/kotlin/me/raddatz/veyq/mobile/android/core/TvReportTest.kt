package me.raddatz.veyq.mobile.android.core

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.NodeReport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A node's report on a TV (ADR 0159's decision log: the DSA's notice mechanism where the content is shown), held without a
 * TV: on a TV the phone's [ReportSheet] is drawn as [TvReportDialog] — its choices pressed as the remote presses them, the
 * privacy notice's passage shown as its address (a TV has no browser to open it in), Send only for a complete form, then
 * the receipt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w1280dp-h720dp-television")
class TvReportTest {
    @get:Rule val compose = createComposeRule()

    private fun press(vararg tags: String) = tags.forEach { compose.onNodeWithTag(it).performSemanticsAction(SemanticsActions.OnClick) }

    @Test
    fun aTvDrawsTheReportForTheRemoteAndSendsIt() {
        val sent = mutableListOf<String>()
        val door = object : ReportSheet.Door {
            override val origin = "https://veyq.example/"
            override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String): NodeReport.Sent {
                sent += "$scope $category $reason"
                return NodeReport.Sent(NodeReport.Outcome.SENT, "ab12cd34", "2026-11-25T10:00:00Z")
            }
        }
        val sheet = ReportSheet(NodeAt("s1", "n1", "MEMORY", "Summer"), door)
        var closed = 0
        compose.setContent {
            VeyqTheme { TvTheme { CompositionLocalProvider(LocalTvForm provides true) { ReportSheet(sheet) { closed++ } } } }
        }
        compose.onNodeWithTag("tv-report").assertExists()
        compose.onNodeWithTag("tv-report-privacy-url").assertTextContains("https://veyq.example/privacy#reporter-contact")
        compose.onNodeWithTag("tv-report-send").assertIsNotEnabled()
        press("tv-report-name", "tv-report-category-TERMS_BREACH", "tv-report-good-faith")
        compose.onNodeWithTag("tv-report-reason").performTextInput("spam")
        compose.onNodeWithTag("tv-report-send").assertIsEnabled()
        press("tv-report-send")
        compose.waitUntil(5_000) { sheet.current.sent != null }
        assertEquals(listOf("NAME TERMS_BREACH spam"), sent)
        compose.onNodeWithTag("tv-report-sent").assertExists()
        press("tv-report-close")
        assertEquals(1, closed)
    }
}
