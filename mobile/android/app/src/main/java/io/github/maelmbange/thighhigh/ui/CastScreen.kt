package io.github.maelmbange.thighhigh.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.cast.CastNotice
import io.github.maelmbange.thighhigh.cast.CastUi
import io.github.maelmbange.thighhigh.cast.Found
import io.github.maelmbange.thighhigh.cast.PairedDevice
import io.github.maelmbange.thighhigh.playback.PulseSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Smartphone

/**
 * The broadcast screen: "Diffuser" makes this phone a radio for the
 * network, "S'abonner" listens to one. Everything else (finding devices,
 * the code, the audio) is in CastController.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastScreen(
    ui: CastUi,
    notice: CastNotice?,
    hasTrack: Boolean,
    heardMs: () -> Long,
    onClose: () -> Unit,
    onBroadcast: () -> Unit,
    onStartBroadcast: () -> Unit,
    onCancelBroadcast: () -> Unit,
    onListen: () -> Unit,
    onSetOpen: (Boolean) -> Unit,
    onForgetPaired: (String) -> Unit,
    onStopBroadcast: () -> Unit,
    onConnect: (Found) -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
    onStopListening: () -> Unit,
    onSwitchChannel: () -> Unit,
    onBack: () -> Unit,
    pulseSource: PulseSource = PulseSource.Bass,
) {
    // Listening takes the whole screen: the player's, without its controls.
    if (ui is CastUi.Listening) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Listening(ui, heardMs, onClose, onStopListening, onSwitchChannel, pulseSource)
        }
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopAppBar(
                title = { Text(stringResource(R.string.cast), color = TextColors.title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            notice?.let {
                Text(
                    noticeText(it),
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag("castNotice"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Slides between steps; within a step, its content just updates.
            // Preparing and running a broadcast are one page: starting it only
            // changes what is shown on it, the page itself stays.
            AnimatedContent(
                ui,
                contentKey = { if (it is CastUi.ReadyToBroadcast || it is CastUi.Broadcasting) CastUi.Broadcasting::class else it::class },
                label = "cast",
                modifier = Modifier.fillMaxSize(),
            ) { state ->
                when (state) {
                    CastUi.Idle -> Choice(onBroadcast, onListen)
                    is CastUi.ReadyToBroadcast, is CastUi.Broadcasting ->
                        Broadcast(state, hasTrack, onSetOpen, onStartBroadcast, onCancelBroadcast, onForgetPaired, onStopBroadcast)
                    is CastUi.Browsing -> Browsing(state, onConnect, onBack)
                    is CastUi.Connecting -> Waiting(stringResource(R.string.cast_connecting, state.name), onCancel)
                    is CastUi.AskingCode -> AskCode(state, onSubmitCode, onCancel)
                    // Drawn above, over the whole screen.
                    is CastUi.Listening -> Unit
                }
            }
        }
    }
}

@Composable
private fun noticeText(notice: CastNotice): String = stringResource(
    when (notice) {
        CastNotice.WrongCode -> R.string.cast_wrong_code
        CastNotice.CodeChanged -> R.string.cast_code_changed
        CastNotice.Full -> R.string.cast_full
        CastNotice.OtherVersion -> R.string.cast_other_version
        CastNotice.Unreachable -> R.string.cast_unreachable
        CastNotice.Ended -> R.string.cast_ended
        CastNotice.Lost -> R.string.cast_lost
        CastNotice.CantStart -> R.string.cast_cant_start
    }
)

/** The two big buttons. */
@Composable
private fun Choice(onBroadcast: () -> Unit, onListen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.cast_intro), color = TextColors.info, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        BigButton(Icons.Default.Podcasts, stringResource(R.string.cast_broadcast), stringResource(R.string.cast_broadcast_hint), onBroadcast)
        BigButton(Icons.Default.Headphones, stringResource(R.string.cast_listen), stringResource(R.string.cast_listen_hint), onListen)
        Text(
            stringResource(R.string.cast_privacy),
            color = TextColors.info,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BigButton(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(20.dp))
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun OpenSwitch(open: Boolean, onSetOpen: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onSetOpen(!open) }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Default.LockOpen else Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.cast_open), color = TextColors.title, style = MaterialTheme.typography.titleMedium)
            // Says plainly what the switch does in its current position.
            Text(
                stringResource(if (open) R.string.cast_open_no_code else R.string.cast_open_needs_code),
                color = TextColors.info,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(open, onCheckedChange = onSetOpen, modifier = Modifier.testTag("castOpen"))
    }
}

/**
 * Diffuser, before and after the start: who may listen, then an explicit
 * start; once running, the code, who listens and the paired devices appear
 * below the same header.
 */
@Composable
private fun Broadcast(
    state: CastUi,
    hasTrack: Boolean,
    onSetOpen: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onForgetPaired: (String) -> Unit,
    onStop: () -> Unit,
) {
    val running = state as? CastUi.Broadcasting
    val name = running?.name ?: (state as CastUi.ReadyToBroadcast).name
    val open = running?.open ?: (state as CastUi.ReadyToBroadcast).open
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Podcasts, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(if (running != null) R.string.cast_visible_as else R.string.cast_will_appear_as, name),
                color = TextColors.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (running != null && !running.visible) {
                Text(stringResource(R.string.cast_not_visible), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            OpenSwitch(open, onSetOpen)
            Text(
                stringResource(
                    when {
                        open -> R.string.cast_open_on
                        running != null -> R.string.cast_open_off
                        else -> R.string.cast_open_off_before
                    }
                ),
                color = TextColors.info,
                textAlign = TextAlign.Center,
            )
            if (running != null) {
                if (!open) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp).testTag("castCode"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.cast_code_label),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            running.code.chunked(3).joinToString(" "),
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    if (running.listeners.isEmpty()) stringResource(R.string.cast_nobody)
                    else stringResource(R.string.cast_listeners, running.listeners.joinToString(", ")),
                    color = if (running.listeners.isEmpty()) TextColors.info else TextColors.genre,
                    textAlign = TextAlign.Center,
                )
            }
            if (!hasTrack) {
                Text(stringResource(R.string.cast_play_something), color = TextColors.info, textAlign = TextAlign.Center)
            }
            if (running != null) PairedList(running.paired, running.listeners, onForgetPaired)
        }
        if (running != null) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.cast_stop_broadcast)) }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("castStart")) {
                Text(stringResource(R.string.cast_start_broadcast))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cast_cancel))
            }
        }
    }
}

/** The devices that typed the code once, each with its own "forget". */
@Composable
private fun PairedList(paired: List<PairedDevice>, listening: List<String>, onForget: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.cast_paired_title),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = TextColors.title,
            style = MaterialTheme.typography.titleSmall,
        )
        if (paired.isEmpty()) {
            Text(
                stringResource(R.string.cast_paired_none),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = TextColors.info,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        paired.forEach { device ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag("castPaired"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Smartphone, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name.ifEmpty { "?" }, color = TextColors.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (device.name in listening) {
                        Text(stringResource(R.string.cast_paired_listening), color = TextColors.genre, style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = { onForget(device.id) }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.cast_forget_one, device.name))
                }
            }
        }
    }
}

@Composable
private fun Browsing(ui: CastUi.Browsing, onConnect: (Found) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.cast_searching), color = TextColors.info)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(ui.found, key = { it.key }) { device ->
                ListItem(
                    headlineContent = { Text(device.name, color = TextColors.title) },
                    supportingContent = {
                        Text(
                            stringResource(
                                when {
                                    !device.compatible -> R.string.cast_device_other_version
                                    device.open -> R.string.cast_device_open
                                    else -> R.string.cast_device_code
                                }
                            ),
                            color = TextColors.genre,
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Podcasts, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.clickable { onConnect(device) }.testTag("castDevice"),
                )
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(stringResource(R.string.cast_cancel))
        }
    }
}

@Composable
private fun Waiting(text: String, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(text, color = TextColors.info, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cast_cancel)) }
    }
}

@Composable
private fun AskCode(ui: CastUi.AskingCode, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.cast_enter_code, ui.name), color = TextColors.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        OutlinedTextField(
            value = code,
            onValueChange = { typed -> code = typed.filter(Char::isDigit).take(6) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 6.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.width(220.dp).testTag("castCodeInput"),
        )
        ui.error?.let { Text(noticeText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Text(stringResource(R.string.cast_code_once), color = TextColors.info, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cast_cancel)) }
            Button(onClick = { onSubmit(code) }, enabled = code.length == 6) { Text(stringResource(R.string.cast_send_code)) }
        }
    }
}

@Composable
private fun Listening(
    ui: CastUi.Listening,
    heardMs: () -> Long,
    onMinimize: () -> Unit,
    onStop: () -> Unit,
    onSwitch: () -> Unit,
    pulseSource: PulseSource,
) {
    // What is heard here can't be captured in a screenshot or screen recording.
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LivePlayerScreen(
        from = ui.name,
        track = ui.track,
        playing = ui.playing,
        positionMs = heardMs,
        onMinimize = onMinimize,
        onStop = onStop,
        onSwitch = onSwitch,
        pulseSource = pulseSource,
    )
}
