package io.github.dshpet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dshpet.mobile.ClipSpec
import io.github.dshpet.mobile.PackManager
import io.github.dshpet.mobile.PackStatus
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.R
import java.util.Locale

/**
 * The animation store. The APK carries a few core clips, everything else is
 * downloaded the first time it is used; this is where you can fetch the whole
 * set, clear it again, or point the app at your own mirror.
 */
@Composable
fun AssetSection(
    options: PetOptions,
    clips: List<ClipSpec>,
    baseUrls: List<String>,
    onChanged: (PetOptions) -> Unit,
) {
    val context = LocalContext.current
    val progress by PackStatus.progress.collectAsState()
    val revision by PackStatus.revision.collectAsState()
    var url by remember(options.packBaseUrl) { mutableStateOf(options.packBaseUrl) }
    var confirmClear by remember { mutableStateOf(false) }

    // revision changes whenever a pack lands, so the counters stay honest.
    val downloaded = remember(revision, clips) {
        clips.count { it.bundled || PackManager.isDownloaded(context, it) }
    }
    val stored = remember(revision) { PackManager.storedBytes(context) }
    val total = clips.size

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.packs_hint, total - clips.count { it.bundled }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.packs_ready, downloaded, total, humanBytes(stored)),
            style = MaterialTheme.typography.bodyMedium)
        if (progress != null) {
            val current = progress!!
            LinearProgressIndicator(
                progress = { if (current.total == 0) 0f else current.done.toFloat() / current.total },
                modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.packs_downloading, current.done, current.total),
                style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { PackManager.shared(context, effectiveUrls(options, baseUrls)).downloadAll(clips) },
                enabled = progress == null && downloaded < total, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.packs_download_all))
            }
            OutlinedButton(onClick = { confirmClear = true }, enabled = progress == null,
                modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.packs_clear))
            }
        }
        if (confirmClear) {
            Text(stringResource(R.string.packs_clear_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    confirmClear = false
                    PackManager.shared(context, effectiveUrls(options, baseUrls)).clear()
                }) { Text(stringResource(R.string.packs_clear)) }
                OutlinedButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
        SettingSwitch(R.string.packs_auto, options.packAuto) {
            onChanged(options.copy(packAuto = it))
        }
        Text(stringResource(R.string.packs_auto_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text(stringResource(R.string.packs_url)) },
            placeholder = { Text(baseUrls.firstOrNull().orEmpty()) })
        Text(stringResource(R.string.packs_url_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { onChanged(options.copy(packBaseUrl = url.trim())) },
            enabled = url.trim() != options.packBaseUrl) {
            Text(stringResource(R.string.packs_url_save))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.packs_managed), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A custom mirror comes first; the built-in ones stay as fallbacks. */
private fun effectiveUrls(options: PetOptions, baseUrls: List<String>): List<String> {
    val custom = options.packBaseUrl.trim()
    return (if (custom.isEmpty()) emptyList() else listOf(custom)) + baseUrls
}

private fun humanBytes(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1024) String.format(Locale.getDefault(), "%.1f GB", mb / 1024)
    else String.format(Locale.getDefault(), "%.0f MB", mb)
}
