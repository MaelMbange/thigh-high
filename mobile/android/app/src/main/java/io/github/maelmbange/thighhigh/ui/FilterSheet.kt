package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

/** The terminal app's filter picker: one tab per field, listing that field's values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    tracks: List<Track>,
    current: Filter?,
    onPick: (Filter) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember { mutableStateOf(current?.field ?: FilterField.Artist) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SecondaryTabRow(selectedTabIndex = field.ordinal) {
            FilterField.entries.forEach { f ->
                Tab(selected = f == field, onClick = { field = f }, text = { Text(f.label()) })
            }
        }
        val values = remember(tracks, field) { filterValues(tracks, field) }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            items(values, key = { it.first }) { (value, count) ->
                val selected = current?.field == field && current.value.equals(value, ignoreCase = true)
                ListItem(
                    modifier = Modifier.clickable { onPick(Filter(field, value)) },
                    headlineContent = { Text(filterValueLabel(value)) },
                    trailingContent = {
                        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        else Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
        }
    }
}
