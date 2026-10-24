package io.github.dshpet.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.R
import kotlin.math.roundToInt

/**
 * Hanging on an edge: a switch and how often it happens.
 *
 * The pet picks a side by itself, keeps roughly half of its body off screen and
 * climbs to a random height for half a minute to a minute and a half, then lets
 * go and falls back to the floor. Off means it stays on the floor like before.
 */
@Composable
fun WallSection(options: PetOptions, onChanged: (PetOptions) -> Unit) {
    SettingSwitch(R.string.wall, options.wall) { onChanged(options.copy(wall = it)) }
    if (options.wall) {
        var minutes by remember(options.wallMinutes) { mutableStateOf(options.wallMinutes.toFloat()) }
        SettingSlider(label = R.string.wall_interval,
            valueText = stringResource(R.string.wall_minutes, minutes.toInt()),
            value = minutes, range = 1f..15f,
            changed = { minutes = it },
            onFinished = { onChanged(options.copy(wallMinutes = minutes.toInt())) })
        var peek by remember(options.wallPeek) { mutableStateOf(options.wallPeek) }
        SettingSlider(label = R.string.wall_peek,
            valueText = stringResource(R.string.percent_value, (peek * 100).roundToInt()),
            value = peek, range = .3f..65f,
            changed = { peek = it },
            onFinished = { onChanged(options.copy(wallPeek = peek)) })
        Text(stringResource(R.string.wall_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
