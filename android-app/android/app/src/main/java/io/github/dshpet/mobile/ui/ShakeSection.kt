package io.github.dshpet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.R
import kotlin.math.roundToInt

/** Shake-to-wobble: one switch plus how hard it should react. */
@Composable
fun ShakeSection(options: PetOptions, onChanged: (PetOptions) -> Unit) {
    SettingSwitch(R.string.shake, options.shake) { onChanged(options.copy(shake = it)) }
    if (!options.shake) return
    SettingSlider(
        R.string.shake_strength,
        "${(options.shakeStrength * 100).roundToInt()}%",
        options.shakeStrength,
        0f..1f,
        { onChanged(options.copy(shakeStrength = it)) },
        {},
    )
}
