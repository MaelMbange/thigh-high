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
import io.github.maelmbange.thighhigh.cast.CastListener
import io.github.maelmbange.thighhigh.cast.CastNotice
import io.github.maelmbange.thighhigh.cast.CastTrack
import io.github.maelmbange.thighhigh.cast.CastUi
import io.github.maelmbange.thighhigh.cast.Found
import io.github.maelmbange.thighhigh.cast.PairedDevice
import io.github.maelmbange.thighhigh.cast.RadioOption
import io.github.maelmbange.thighhigh.cast.CastChannel
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
    private var radios by mutableStateOf<List<RadioOption>>(emptyList())

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
                onForgetAllPaired = { calls += "forgetAll" },
                onStopBroadcast = { calls += "stop" },
                onConnect = { calls += "connect:${it.name}" },
                onSubmitCode = { calls += "code:$it" },
                onCancel = { calls += "cancel" },
                onStopListening = { calls += "unlisten" },
                onSwitchChannel = { calls += "switch" },
                onBack = { calls += "back" },
                radios = radios,
                onSetRadio = { id, on -> calls += "radio:$id:$on" },
                onTune = { calls += "tune:$it" },
            )
        }
        rule.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun playlistsAreOfferedAsRadios() {
        ui = CastUi.Broadcasting("Pixel", open = true, code = "123456", listeners = listOf(CastListener("p1", "Tablette", radio = "Rock")), paired = listOf(PairedDevice("p1", "Tablette")), visible = true)
        radios = listOf(RadioOption("a", "Rock", 12, on = true), RadioOption("b", "Vide", 0, on = false), RadioOption("c", "Calme", 3, on = false))
        show()
        rule.onNodeWithText("12 morceaux · 1 à l'écoute").assertExists()
        rule.onNodeWithText("À l'écoute de « Rock »").assertExists()
        rule.onAllNodesWithTag("castRadio")[1].assertIsNotEnabled()
        rule.onNodeWithText("Calme").performClick()
        rule.onAllNodesWithTag("castRadio")[0].performClick()
        assertEquals(listOf("radio:c:true", "radio:a:false"), calls)
    }

    @Test
    fun listenerPicksAChannel() {
        ui = CastUi.Listening("PC", track = null, playing = true, radios = listOf(CastChannel(1, "Rock", listeners = 2), CastChannel(2, "Calme")), channel = 1, liveListeners = 1)
        show()
        rule.onNodeWithText("Rock · 2").assertExists()
        rule.onNodeWithText("En direct · 1").performClick()
        rule.onNodeWithText("Calme").performClick()
        assertEquals(listOf("tune:0", "tune:2"), calls)
    }

    @Test
    fun noRadiosNoChannelRow() {
        ui = CastUi.Listening("PC", track = null, playing = true)
        show()
        rule.onNodeWithTag("castChannels").assertDoesNotExist()
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
        ui = CastUi.Broadcasting(
            "Pixel", open = false, code = "123456", listeners = listOf(CastListener("aa", "Wetwork")), visible = true,
            paired = listOf(PairedDevice("aa", "Wetwork")),
        )
        show()
        rule.onNodeWithTag("castCode").assertExists()
        rule.onNodeWithText("123 456").assertExists()
        rule.onNodeWithText("Wetwork").assertExists()
        rule.onNodeWithText("À l'écoute").assertExists()
        rule.onNodeWithTag("castOpen").performClick()
        assertEquals(listOf("open:true"), calls)

        ui = CastUi.Broadcasting("Pixel", open = true, code = "123456", listeners = emptyList(), visible = true)
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Activé : tout le monde peut écouter, sans code").assertExists()
        rule.onNodeWithTag("castCode").assertDoesNotExist()
        rule.onNodeWithText("Arrêter la diffusion").performClick()
        assertEquals("stop", calls.last())
    }

    @Test
    fun pairedDevicesAreForgottenOneByOne() {
        ui = CastUi.Broadcasting(
            "Pixel", open = true, code = "123456", visible = true,
            listeners = listOf(CastListener("aa", "Wetwork"), CastListener(null, "Invité")),
            paired = listOf(PairedDevice("aa", "Wetwork"), PairedDevice("bb", "Tablette")),
        )
        show()
        // One line per device: the paired one listening keeps its line.
        rule.onAllNodesWithTag("castDeviceRow").assertCountEquals(3)
        rule.onNodeWithText("À l'écoute (ouvert)").assertExists()
        rule.onNodeWithText("Appairé").assertExists()
        rule.onNodeWithContentDescription("Oublier Invité").assertDoesNotExist()
        rule.onNodeWithContentDescription("Oublier Tablette").performClick()
        rule.onNodeWithTag("castForgetAll").performClick()
        assertEquals(listOf("forget:bb", "forgetAll"), calls)
    }

    @Test
    fun devicesAreListedAndJoined() {
        ui = CastUi.Browsing(listOf(Found("PC", "PC", "192.168.1.2", 4000, "abc", open = true, compatible = true)))
        show()
        rule.onNodeWithText("PC").performClick()
        // The back arrow does what "Annuler" does: back to the two buttons.
        rule.onNodeWithContentDescription("Retour").performClick()
        assertEquals(listOf("connect:PC", "back"), calls)
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
        // Nor any way back: only stopping or changing channel leaves.
        rule.onNodeWithContentDescription("Réduire").assertDoesNotExist()
        rule.onNodeWithContentDescription("Retour").assertDoesNotExist()
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
