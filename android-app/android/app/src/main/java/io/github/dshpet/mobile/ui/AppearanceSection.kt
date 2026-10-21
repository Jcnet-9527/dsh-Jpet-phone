package io.github.dshpet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.R
import kotlin.math.round
import kotlin.math.roundToInt

/** Size is committed when the finger leaves the slider (commit_policy: on_finish). */
@Composable
fun AppearanceSection(options: PetOptions, onChanged: (PetOptions) -> Unit) {
    var draft by remember(options.size) { mutableStateOf(options.size) }
    SettingSlider(label = R.string.pet_size,
        valueText = "${(draft * 100).toInt()}%",
        value = draft,
        range = .6f..1.6f,
        changed = { draft = it },
        onFinished = { onChanged(options.copy(size = draft)) })

    // Snapped to 10% steps: predictable, and easy to talk about in feedback.
    var edge by remember(options.edgeAllowance) { mutableStateOf(options.edgeAllowance) }
    SettingSlider(label = R.string.edge_allowance,
        valueText = stringResource(R.string.percent_value, (edge * 100).roundToInt()),
        value = edge,
        range = PetOptions.MIN_EDGE_ALLOWANCE..PetOptions.MAX_EDGE_ALLOWANCE,
        changed = { edge = it },
        onFinished = {
            val snapped = (round(edge * 10) / 10f)
                .coerceIn(PetOptions.MIN_EDGE_ALLOWANCE, PetOptions.MAX_EDGE_ALLOWANCE)
            edge = snapped
            onChanged(options.copy(edgeAllowance = snapped))
        })
}
