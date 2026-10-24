package io.github.dshpet.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.dshpet.mobile.VoiceEngine
import io.github.dshpet.mobile.VoiceModels
import io.github.dshpet.mobile.VoiceStatus
import io.github.dshpet.mobile.VoiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Voice mode and voice input: two independent switches, the speaking voice and
 * the speed, then the on-device models (recognizer + one speaker per timbre).
 * Nothing here records or synthesises live audio except the preview button.
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
    var audition by remember { mutableStateOf(0) }
    var previewing by remember { mutableStateOf(false) }

    val store = remember(revision, options.packBaseUrl) {
        val custom = options.packBaseUrl.trim()
        VoiceStore.shared(context,
            (if (custom.isEmpty()) emptyList() else listOf(custom)) + baseUrls)
    }
    val asrReady = remember(revision) { store.isReady(VoiceModels.ASR) }
    val spec = VoiceModels.ttsSpec(options.voiceTimbre)
    val timbreReady = remember(revision, options.voiceTimbre) { store.isReady(spec) }
    val readyCount = listOf(asrReady, timbreReady).count { it }
    val stored = remember(revision) { store.storedBytes() }

    // Voice mode: everything the pet says in a bubble is read out.
    SettingSwitch(R.string.voice_speak_switch, options.voiceSpeak) {
        onChanged(options.copy(voiceSpeak = it))
    }
    Text(stringResource(R.string.voice_speak_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    // Voice input: the hold-to-talk button that lives beside the pet.
    SettingSwitch(R.string.voice_chat_switch, options.voiceChat) {
        onChanged(options.copy(voiceChat = it))
    }
    Text(stringResource(R.string.voice_chat_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (options.voiceChat && !micGranted) {
        Text(stringResource(R.string.voice_permission_hint),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRequestMic) {
            Text(stringResource(R.string.voice_grant_mic))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.voice_timbre), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.voice_timbre_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PetOptions.TIMBRES.forEach { timbre ->
                FilterChip(
                    selected = options.voiceTimbre == timbre,
                    onClick = { onChanged(options.copy(voiceTimbre = timbre)) },
                    label = { Text(stringResource(timbreLabel(timbre))) })
            }
        }
        if (!timbreReady) {
            Text(stringResource(R.string.voice_timbre_missing, humanBytes(spec.bytes)),
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { store.downloadAll(listOf(spec)) },
                enabled = progress == null) {
                Text(stringResource(R.string.voice_timbre_download))
            }
        } else {
            OutlinedButton(onClick = { if (!previewing) audition++ },
                enabled = !previewing) {
                Text(stringResource(
                    if (previewing) R.string.voice_models_busy else R.string.voice_preview))
            }
        }
        Text(stringResource(R.string.voice_models), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.voice_models_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.voice_asr_model) + " · " + stringResource(
            if (asrReady) R.string.voice_asr_ready else R.string.voice_asr_missing),
            style = MaterialTheme.typography.bodyMedium)
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
            Button(onClick = { store.downloadAll(listOf(VoiceModels.ASR, spec)) },
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

    var speed by remember(options.voiceSpeed) { mutableStateOf(options.voiceSpeed) }
    SettingSlider(label = R.string.voice_speed,
        valueText = stringResource(R.string.voice_speed_value,
            String.format(Locale.getDefault(), "%.1f", speed)),
        value = speed,
        range = PetOptions.MIN_VOICE_SPEED..PetOptions.MAX_VOICE_SPEED,
        changed = { speed = it },
        onFinished = { onChanged(options.copy(voiceSpeed = speed)) })

    // The preview speaks one fixed line with the selected voice.
    val sample = stringResource(R.string.voice_preview_text)
    LaunchedEffect(audition) {
        if (audition == 0) return@LaunchedEffect
        previewing = true
        try {
            withContext(Dispatchers.IO) {
                val speaker = runCatching {
                    VoiceEngine.speaker(store.modelDir(spec), options.voiceTimbre)
                }.getOrNull() ?: return@withContext
                try {
                    val pcm = speaker.synthesize(sample, options.voiceSpeed) ?: return@withContext
                    playPreview(pcm, speaker.sampleRate)
                } finally {
                    speaker.close()
                }
            }
        } finally {
            previewing = false
        }
    }
}

private fun timbreLabel(timbre: String): Int = when (timbre) {
    PetOptions.TIMBRE_MELO -> R.string.voice_timbre_melo
    PetOptions.TIMBRE_XIAOYA -> R.string.voice_timbre_xiaoya
    PetOptions.TIMBRE_CHAOWEN -> R.string.voice_timbre_chaowen
    else -> R.string.voice_timbre_baker
}

/** Plays one preview clip, blocking until it is done, then cleans up. */
private suspend fun playPreview(pcm: ShortArray, sampleRate: Int) {
    val track = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        (pcm.size * 2).coerceAtLeast(8192), AudioTrack.MODE_STATIC)
    try {
        track.write(pcm, 0, pcm.size)
        track.play()
        delay(pcm.size * 1000L / sampleRate.coerceAtLeast(1) + 150)
    } finally {
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}

private fun humanBytes(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1024) String.format(Locale.getDefault(), "%.1f GB", mb / 1024)
    else String.format(Locale.getDefault(), "%.0f MB", mb)
}
