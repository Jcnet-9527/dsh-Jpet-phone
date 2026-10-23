package io.github.dshpet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.R

/**
 * The overlay window is a rectangle roughly four times the pet's own area, so
 * taps in the transparent margin used to fall on us instead of the app below.
 * Android cannot pass a touch through per pixel, so this switch trades the
 * pet's own drag/click for "never blocks anything".
 */
@Composable
fun TouchSection(options: PetOptions, onChanged: (PetOptions) -> Unit) {
    SettingSwitch(R.string.touch_through, options.touchThrough) {
        onChanged(options.copy(touchThrough = it))
    }
}
