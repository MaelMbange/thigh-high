package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.maelmbange.thighhigh.R
import io.github.maelmbange.thighhigh.download.DownloadJob
import io.github.maelmbange.thighhigh.download.DownloadStatus

@Composable
fun DownloadScreen(
    jobs: List<DownloadJob>,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val submit = {
        if (url.isNotBlank()) {
            onDownload(url)
            url = ""
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.download_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            trailingIcon = {
                IconButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }) {
                    Icon(Icons.Default.ContentPaste, stringResource(R.string.paste))
                }
            },
        )
        Button(
            onClick = submit,
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Icon(Icons.Default.Download, null)
            Text(stringResource(R.string.download), Modifier.padding(start = 8.dp))
        }
        if (jobs.isEmpty()) {
            Text(
                stringResource(R.string.download_hint),
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs, key = { it.id }) { job ->
                JobCard(job, onCancel = { onCancel(job.id) }, onDismiss = { onDismiss(job.id) })
            }
        }
    }
}

@Composable
private fun JobCard(job: DownloadJob, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(job.title ?: job.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when (job.status) {
                    DownloadStatus.Waiting -> stringResource(R.string.download_waiting)
                    DownloadStatus.Downloading -> job.percent?.let { "%.0f %%".format(it) }
                        ?: stringResource(R.string.download_starting)
                    DownloadStatus.Saving -> stringResource(R.string.download_saving)
                    DownloadStatus.Done -> stringResource(R.string.download_done)
                    DownloadStatus.Canceled -> stringResource(R.string.download_canceled)
                    DownloadStatus.Failed -> job.error ?: stringResource(R.string.download_failed)
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == DownloadStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    job.status == DownloadStatus.Downloading && job.percent != null ->
                        LinearProgressIndicator(progress = { job.percent / 100f }, Modifier.fillMaxWidth())
                    job.isActive -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (job.isActive) {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            } else {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.dismiss)) }
            }
        }
    }
}
