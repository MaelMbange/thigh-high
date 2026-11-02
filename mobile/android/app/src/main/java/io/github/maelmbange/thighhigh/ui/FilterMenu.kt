package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.library.Filter
import io.github.maelmbange.thighhigh.library.FilterField
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.library.filterValues
import io.github.maelmbange.thighhigh.library.searchKey
import kotlinx.coroutines.launch

@Composable
fun FilterField.label(): String = stringResource(
    when (this) {
        FilterField.Artist -> R.string.filter_artist
        FilterField.Genre -> R.string.filter_genre
        FilterField.Album -> R.string.filter_album
    }
)

val FilterField.icon: ImageVector
    get() = when (this) {
        FilterField.Artist -> Icons.Default.Person
        FilterField.Genre -> Icons.Default.Category
        FilterField.Album -> Icons.Default.Album
    }

@Composable
fun filterValueLabel(value: String): String =
    if (value == FilterField.UNKNOWN) stringResource(R.string.filter_unknown) else value

/** How the values of a field are listed. */
private enum class Order { Name, Count }

/**
 * The terminal app's filter picker, as a sheet rising from the bottom: a
 * segmented control for artist / genre / album, a search through that
 * field's values, A–Z or by number of tracks, and the values with their
 * counts. Picking one applies it and closes the sheet; "Tout afficher"
 * removes the filter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    tracks: List<Track>,
    current: Filter?,
    onPick: (Filter?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var field by rememberSaveable { mutableStateOf(current?.field ?: FilterField.Artist) }
    var search by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(Order.Name) }
    // Applies the choice at once, then lets the sheet slide away.
    fun pick(filter: Filter?) {
        onPick(filter)
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.filter),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextColors.title,
                )
                TextButton(onClick = { pick(null) }, enabled = current != null) {
                    Text(stringResource(R.string.filter_show_all))
                }
            }

            val counts = remember(tracks) { FilterField.entries.associateWith { filterValues(tracks, it).size } }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilterField.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = f == field,
                        onClick = {
                            field = f
                            search = ""
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, FilterField.entries.size),
                        icon = { SegmentedButtonDefaults.Icon(f == field) { Icon(f.icon, null, Modifier.size(18.dp)) } },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            activeContentColor = TextColors.title,
                            activeBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = TextColors.info,
                            inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text("${f.label()} · ${counts.getValue(f)}", maxLines = 1)
                    }
                }
            }

            TextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.filter_search, field.label().lowercase())) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, stringResource(R.string.search_clear)) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Order.entries.forEach { o ->
                    FilterChip(
                        selected = order == o,
                        onClick = { order = o },
                        label = { Text(stringResource(if (o == Order.Name) R.string.filter_order_name else R.string.filter_order_count)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = TextColors.title,
                        ),
                    )
                }
            }

            // The values of the field picked; the list crossfades when it changes.
            AnimatedContent(
                field,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "values",
            ) { shownField ->
                val all = remember(tracks, shownField) { filterValues(tracks, shownField) }
                val values = remember(all, search, order) {
                    val key = searchKey(search.trim())
                    val found = all.filter { (value, _) ->
                        key.isEmpty() || (value != FilterField.UNKNOWN && key in searchKey(value))
                    }
                    if (order == Order.Count) found.sortedByDescending { it.second } else found
                }
                val listState = rememberLazyListState()
                LaunchedEffect(search, order) { listState.scrollToItem(0) }
                if (values.isEmpty()) {
                    Text(
                        stringResource(R.string.filter_no_match),
                        Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = TextColors.info,
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(top = 4.dp), state = listState) {
                    items(values, key = { it.first }) { (value, count) ->
                        val selected = current?.field == shownField && current.value.equals(value, ignoreCase = true)
                        ValueRow(value, count, selected) { pick(Filter(shownField, value)) }
                    }
                }
            }
        }
    }
}

/** One value: its initial in a circle, its name, its number of tracks, a check when picked. */
@Composable
private fun ValueRow(value: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val unknown = value == FilterField.UNKNOWN
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (selected) accent else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Default.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(
                    if (unknown) "?" else value.first().uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextColors.info,
                )
            }
        }
        Text(
            filterValueLabel(value),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            fontStyle = if (unknown) FontStyle.Italic else null,
            color = if (unknown) TextColors.info else TextColors.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp)
                .height(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = TextColors.info)
        }
    }
}
