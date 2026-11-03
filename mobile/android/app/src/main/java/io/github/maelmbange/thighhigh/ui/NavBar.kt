package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A tab of the bottom bar. */
data class NavItem(val icon: ImageVector, val label: String)

/**
 * The bottom bar: a floating rounded bar whose highlight is a pill gliding
 * under the tabs. The pill follows the pages as they slide ([position], the
 * page shown with its fraction), so a swipe between tabs drags it along
 * with the finger. The tab under it shows its name next to its icon; the
 * others keep only their icon. A tab pops a little when it is picked.
 */
@Composable
fun NavBar(items: List<NavItem>, position: () -> Float, onSelect: (Int) -> Unit) {
    val selected by remember { derivedStateOf { position().roundToInt() } }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(12.dp, RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight().padding(6.dp)) {
            val slot = maxWidth / items.size
            val slotPx = with(LocalDensity.current) { slot.toPx() }
            // The highlight, moved in the layout phase only: following the pages costs no recomposition.
            Box(
                Modifier
                    .offset { IntOffset((position() * slotPx).roundToInt(), 0) }
                    .width(slot)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(accent.copy(alpha = 0.22f)),
            )
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                items.forEachIndexed { i, item ->
                    NavTab(item, i == selected, Modifier.weight(1f)) { onSelect(i) }
                }
            }
        }
    }
}

@Composable
private fun NavTab(item: NavItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else TextColors.info,
        tween(250),
        label = "tab tint",
    )
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.8f)
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        }
    }
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            // The pill is the feedback: no ripple on top of it.
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                item.icon,
                contentDescription = if (selected) null else item.label,
                tint = tint,
                modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            )
            AnimatedVisibility(
                selected,
                enter = expandHorizontally(tween(260)) + fadeIn(tween(260, delayMillis = 60)),
                exit = shrinkHorizontally(tween(200)) + fadeOut(tween(120)),
            ) {
                Text(
                    item.label,
                    Modifier.padding(start = 8.dp),
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}
