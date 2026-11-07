package app.honmani.reader.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.honmani.reader.ui.theme.LocalMotionEnabled
import app.honmani.reader.ui.theme.motionSpring

/**
 * A progress bar without Material's dot at the end of the track (distracting next to the text).
 * It glides to its value when that changes, and grows from empty when it appears, unless [growIn]
 * is off (rows of a long list, which appear again with every scroll). Jumps when animations are off.
 */
@Composable
fun ProgressBar(progress: () -> Float, modifier: Modifier = Modifier, growIn: Boolean = true) {
    val motion = LocalMotionEnabled.current
    var appeared by remember { mutableStateOf(!growIn || !motion) }
    LaunchedEffect(Unit) { appeared = true }
    val shown by animateFloatAsState(
        targetValue = if (appeared) progress() else 0f,
        animationSpec = motionSpring(),
        label = "progress",
    )
    LinearProgressIndicator(progress = { shown }, modifier = modifier, drawStopIndicator = {})
}
