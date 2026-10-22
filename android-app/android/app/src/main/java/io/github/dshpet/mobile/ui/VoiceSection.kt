package io.github.dshpet.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.R
import io.github.dshpet.mobile.VoiceModels
import io.github.dshpet.mobile.VoiceStatus
import io.github.dshpet.mobile.VoiceStore
import java.util.Locale

/**
 * The voice conversation settings: the enable switch, the reply speech toggle,
 * the speed slider, and the on-device model store (download, status, delete).
 * Nothing here records or synthesises audio; it only manages the files the
 * voice module reads.
 */
@Composable
fun VoiceSection(
    options: PetOptions,
    baseUrls: List<String>,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onChanged: (PetOptions) -> Unit,
) {
    val context = LocalContext.current
    val progress by VoiceStatus.progress.collectAsState()
    val revision by VoiceStatus.revision.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    val store = remember(revision, options.packBaseUrl) {
        val custom = options.packBaseUrl.trim()
        VoiceStore.shared(context,
            (if (custom.isEmpty()) emptyList() else listOf(custom)) + baseUrls)
    }
    val asrReady = remember(revision) { store.isReady(VoiceModels.ASR) }
    val ttsReady = remember(revision) { store.isReady(VoiceModels.TTS) }
    val readyCount = listOf(asrReady, ttsReady).count { it }
    val stored = remember(revision) { store.storedBytes() }

    SettingSwitch(R.string.voice, options.voiceChat) { onChanged(options.copy(voiceChat = it)) }
    if (!options.voiceChat) return
    Text(stringResource(R.string.voice_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (!micGranted) {
        Text(stringResource(R.string.voice_permission_hint),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRequestMic) {
            Text(stringResource(R.string.voice_grant_mic))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.voice_models), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.voice_models_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when {
            readyCount == 2 -> stringResource(R.string.voice_models_ready, humanBytes(stored))
            readyCount > 0 -> stringResource(R.string.voice_models_partial, readyCount)
            else -> stringResource(R.string.voice_models_missing)
        }, style = MaterialTheme.typography.bodyMedium)
        if (progress != null) {
            val (done, total) = progress!!
            LinearProgressIndicator(
                progress = { if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.voice_model_progress,
                    if (total <= 0) 0 else (done * 100 / total).toInt()),
                style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { store.downloadAll(listOf(VoiceModels.ASR, VoiceModels.TTS)) },
                enabled = progress == null && readyCount < 2, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_models_download))
            }
            OutlinedButton(onClick = { confirmClear = true }, enabled = progress == null && readyCount > 0,
                modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_models_clear))
            }
        }
        if (confirmClear) {
            Text(stringResource(R.string.voice_models_clear_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    confirmClear = false
                    store.clear()
                }) { Text(stringResource(R.string.voice_models_clear)) }
                OutlinedButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }

    SettingSwitch(R.string.voice_speak_switch, options.voiceSpeak) {
        onChanged(options.copy(voiceSpeak = it))
    }
    Text(stringResource(R.string.voice_speak_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    var speed by remember(options.voiceSpeed) { mutableStateOf(options.voiceSpeed) }
    SettingSlider(label = R.string.voice_speed,
        valueText = stringResource(R.string.voice_speed_value,
            String.format(Locale.getDefault(), "%.1f", speed)),
        value = speed,
        range = PetOptions.MIN_VOICE_SPEED..PetOptions.MAX_VOICE_SPEED,
        changed = { speed = it },
        onFinished = { onChanged(options.copy(voiceSpeed = speed)) })
}

private fun humanBytes(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1024) String.format(Locale.getDefault(), "%.1f GB", mb / 1024)
    else String.format(Locale.getDefault(), "%.0f MB", mb)
}
