package io.github.maelmbange.thighhigh.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import io.github.maelmbange.thighhigh.download.DownloadJob
import io.github.maelmbange.thighhigh.download.DownloadStatus
import io.github.maelmbange.thighhigh.download.Match
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DownloadCardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aSongDownloadedAgainOffersToReplaceTheOldOne() {
        val job = DownloadJob(
            id = "1", url = "https://youtu.be/x", title = "One More Time (Official Audio)",
            status = DownloadStatus.Done, trackId = 9,
            similar = Match(4, "One More Time", "Daft Punk"),
        )
        var replaced: DownloadJob? = null
        var kept: String? = null
        rule.setContent {
            ThighHighTheme {
                DownloadScreen(
                    jobs = listOf(job), onDownload = {}, onCancel = {}, onDismiss = {},
                    onKeepBoth = { kept = it }, onReplaceOld = { replaced = it },
                )
            }
        }
        rule.onNodeWithText("« One More Time » de Daft Punk", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Remplacer l'ancienne").performClick()
        assertEquals(job, replaced)
        rule.onNodeWithText("Garder les deux").performClick()
        assertEquals("1", kept)
    }

    @Test fun aLinkAlreadyDownloadedCanStillBeDownloaded() {
        val job = DownloadJob(
            id = "2", url = "https://youtu.be/x", status = DownloadStatus.Duplicate,
            existing = Match(4, "One More Time", null),
        )
        var anyway: String? = null
        rule.setContent {
            ThighHighTheme {
                DownloadScreen(jobs = listOf(job), onDownload = {}, onCancel = {}, onDismiss = {}, onDownloadAnyway = { anyway = it })
            }
        }
        rule.onNodeWithText("Déjà dans la bibliothèque : « One More Time »").assertIsDisplayed()
        rule.onNodeWithText("Télécharger quand même").performClick()
        assertEquals("2", anyway)
    }
}
