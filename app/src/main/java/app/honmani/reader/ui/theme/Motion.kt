package app.honmani.reader.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the interface may animate. Off by the Appearance switch, in E-ink mode (the screen smears) and when the phone's
 * animations are off (Accessibility, "Remove animations"). Every animation of the interface goes
 * through this, directly or through the helpers below; the reader page itself never animates.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/**
 * Animations are on unless the Appearance switch is off, E-ink mode is on or the system's animator
 * duration scale is 0.
 */
fun motionEnabled(eInk: Boolean, animatorScale: Float, setting: Boolean = true): Boolean =
    setting && !eInk && animatorScale > 0f

/** The system's animator duration scale, read again when the user changes it. */
@Composable
fun rememberAnimatorScale(): Float {
    val context = LocalContext.current
    var scale by remember(context) { mutableFloatStateOf(readAnimatorScale(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorScale(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return scale
}

private fun readAnimatorScale(context: android.content.Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** A calm spring (no overshoot) for values; a jump when animations are off. */
@Composable
fun <T> motionSpring(
    dampingRatio: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMediumLow,
): FiniteAnimationSpec<T> =
    if (LocalMotionEnabled.current) spring(dampingRatio, stiffness) else snap()

/** A timed animation; a jump when animations are off. */
@Composable
fun <T> motionTween(durationMs: Int, easing: Easing = FastOutSlowInEasing): FiniteAnimationSpec<T> =
    if (LocalMotionEnabled.current) tween(durationMs, easing = easing) else snap()

/** Shrinks to [pressedScale] while pressed and springs back, by a graphics layer only (no layout). */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.97f): Modifier {
    if (!LocalMotionEnabled.current) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
        label = "press scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
