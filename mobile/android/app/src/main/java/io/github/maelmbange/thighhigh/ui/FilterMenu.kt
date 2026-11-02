package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.library.Filter
import io.github.maelmbange.thighhigh.library.FilterField
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.library.filterValues

@Composable
fun FilterField.label(): String = stringResource(
    when (this) {
        FilterField.Artist -> R.string.filter_artist
        FilterField.Genre -> R.string.filter_genre
        FilterField.Album -> R.string.filter_album
    }
)

@Composable
fun filterValueLabel(value: String): String =
    if (value == FilterField.UNKNOWN) stringResource(R.string.filter_unknown) else value

/**
 * The terminal app's filter picker, as a menu dropping from the filter
 * button: a chip per field, then that field's values with their counts.
 */
@Composable
fun FilterMenu(
    expanded: Boolean,
    tracks: List<Track>,
    current: Filter?,
    onPick: (Filter) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember(expanded) { mutableStateOf(current?.field ?: FilterField.Artist) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 420.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterField.entries.forEach { f ->
                FilterChip(selected = f == field, onClick = { field = f }, label = { Text(f.label()) })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        val values = remember(tracks, field) { filterValues(tracks, field) }
        values.forEach { (value, count) ->
            val selected = current?.field == field && current.value.equals(value, ignoreCase = true)
            DropdownMenuItem(
                text = { Text(filterValueLabel(value), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = { onPick(Filter(field, value)) },
                trailingIcon = {
                    if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    else Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }
    }
}
