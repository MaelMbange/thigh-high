package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.playback.PulseSource

/** The app's settings: for now, what the player's animation follows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    pulseSource: PulseSource,
    onPulseSource: (PulseSource) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.settings),
                Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge,
                color = TextColors.title,
            )
            Text(
                stringResource(R.string.pulse_title),
                Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.pulse_hint),
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = TextColors.info,
            )
            Column(Modifier.selectableGroup()) {
                PulseSource.entries.forEach { source ->
                    val (name, detail) = labels(source)
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = source == pulseSource,
                                onClick = { onPulseSource(source) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 8.dp),
                        leadingContent = { RadioButton(selected = source == pulseSource, onClick = null) },
                        headlineContent = { Text(stringResource(name)) },
                        supportingContent = { Text(stringResource(detail)) },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            headlineColor = TextColors.title,
                            supportingColor = TextColors.info,
                        ),
                    )
                }
            }
        }
    }
}

private fun labels(source: PulseSource): Pair<Int, Int> = when (source) {
    PulseSource.Bass -> R.string.pulse_bass to R.string.pulse_bass_detail
    PulseSource.Drums -> R.string.pulse_drums to R.string.pulse_drums_detail
    PulseSource.Voice -> R.string.pulse_voice to R.string.pulse_voice_detail
    PulseSource.Highs -> R.string.pulse_highs to R.string.pulse_highs_detail
    PulseSource.All -> R.string.pulse_all to R.string.pulse_all_detail
}
