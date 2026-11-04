package io.github.maelmbange.thighhigh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.snapshots.Snapshot
import io.github.maelmbange.thighhigh.cast.CastNotice
import io.github.maelmbange.thighhigh.cast.CastTrack
import io.github.maelmbange.thighhigh.cast.CastUi
import io.github.maelmbange.thighhigh.cast.Found
import io.github.maelmbange.thighhigh.cast.PairedDevice
import io.github.maelmbange.thighhigh.cast.toText
import io.github.maelmbange.thighhigh.lyrics.LyricLine
import io.github.maelmbange.thighhigh.lyrics.Lyrics
import io.github.maelmbange.thighhigh.lyrics.parseLrc
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CastScreenTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private var ui by mutableStateOf<CastUi>(CastUi.Idle)

    private fun show(notice: CastNotice? = null) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CastScreen(
                ui = ui,
                notice = notice,
                hasTrack = false,
                heardMs = { 1_500 },
                onClose = { calls += "close" },
                onBroadcast = { calls += "broadcast" },
                onStartBroadcast = { calls += "start" },
                onCancelBroadcast = { calls += "cancelBroadcast" },
                onListen = { calls += "listen" },
                onSetOpen = { calls += "open:$it" },
                onForgetPaired = { calls += "forget:$it" },
                onStopBroadcast = { calls += "stop" },
                onConnect = { calls += "connect:${it.name}" },
                onSubmitCode = { calls += "code:$it" },
                onCancel = { calls += "cancel" },
                onStopListening = { calls += "unlisten" },
                onSwitchChannel = { calls += "switch" },
                onBack = { calls += "back" },
            )
        }
        rule.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun twoButtons() {
        show()
        rule.onNodeWithText("Diffuser").performClick()
        rule.onNodeWithText("S'abonner").performClick()
        assertEquals(listOf("broadcast", "listen"), calls)
    }

    @Test
    fun broadcastStartsOnlyWithTheButton() {
        ui = CastUi.ReadyToBroadcast("Pixel", open = false)
        show()
        rule.onNodeWithTag("castCode").assertDoesNotExist()
        rule.onNodeWithTag("castOpen").performClick()
        rule.onNodeWithText("Annuler").assertDoesNotExist()
        rule.onNodeWithText("Démarrer la diffusion").performClick()
        assertEquals(listOf("open:true", "start"), calls)
    }

    @Test
    fun broadcastShowsTheCodeUntilOpen() {
        ui = CastUi.Broadcasting("Pixel", open = false, code = "123456", listeners = listOf("Wetwork"), visible = true)
        show()
        rule.onNodeWithTag("castCode").assertExists()
        rule.onNodeWithText("123 456").assertExists()
        rule.onNodeWithText("À l'écoute : Wetwork").assertExists()
        rule.onNodeWithTag("castOpen").performClick()
        assertEquals(listOf("open:true"), calls)

        ui = CastUi.Broadcasting("Pixel", open = true, code = "123456", listeners = emptyList(), visible = true)
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Tout le monde sur ce réseau peut écouter, sans code.").assertExists()
        rule.onNodeWithTag("castCode").assertDoesNotExist()
        rule.onNodeWithText("Arrêter la diffusion").performClick()
        assertEquals("stop", calls.last())
    }

    @Test
    fun pairedDevicesAreForgottenOneByOne() {
        ui = CastUi.Broadcasting(
            "Pixel", open = false, code = "123456", listeners = listOf("Wetwork"), visible = true,
            paired = listOf(PairedDevice("aa", "Wetwork"), PairedDevice("bb", "Tablette")),
        )
        show()
        rule.onAllNodesWithTag("castPaired").assertCountEquals(2)
        rule.onNodeWithContentDescription("Oublier Tablette").performClick()
        assertEquals(listOf("forget:bb"), calls)
    }

    @Test
    fun devicesAreListedAndJoined() {
        ui = CastUi.Browsing(listOf(Found("PC", "PC", "192.168.1.2", 4000, "abc", open = true, compatible = true)))
        show()
        rule.onNodeWithText("PC").performClick()
        assertEquals(listOf("connect:PC"), calls)
    }

    @Test
    fun codeNeedsSixDigits() {
        ui = CastUi.AskingCode("PC", CastNotice.WrongCode)
        show()
        rule.onNodeWithText("Code incorrect, réessaie.").assertExists()
        rule.onNodeWithText("Valider").assertIsNotEnabled()
        rule.onNodeWithTag("castCodeInput").performTextInput("12a34 5678")
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Valider").assertIsEnabled().performClick()
        assertEquals(listOf("code:123456"), calls)
    }

    @Test
    fun listeningShowsTrackAndLyrics() {
        val lyrics = Lyrics.Synced(listOf(LyricLine(1_000, "première ligne"), LyricLine(5_000, "deuxième")))
        ui = CastUi.Listening(
            "PC",
            CastTrack("Titre", "Artiste", "", 200_000, null, CoverPalette.Default, lyrics),
            playing = true,
        )
        show()
        rule.onNodeWithText("En direct de PC").assertExists()
        rule.onNodeWithText("Titre").assertExists()
        rule.onNodeWithTag("cover").assertExists()
        // No player controls: the broadcaster chooses what plays.
        rule.onNodeWithContentDescription("Pause").assertDoesNotExist()
        rule.onNodeWithContentDescription("Afficher les paroles").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("première ligne").assertExists()
        rule.onNodeWithText("Changer de canal").performClick()
        rule.onNodeWithText("Arrêter l'écoute").performClick()
        assertEquals(listOf("switch", "unlisten"), calls)
    }

    @Test
    fun lyricsSurviveTheTrip() {
        val lines = listOf(LyricLine(1_230, "un"), LyricLine(61_000, "deux"), LyricLine(3_599_990, "trois"))
        assertEquals(lines, parseLrc(Lyrics.Synced(lines).toText()!!))
    }
}
