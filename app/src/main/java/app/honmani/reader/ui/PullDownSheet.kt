package app.honmani.reader.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A bottom sheet around content that scrolls (put the scroll inside, with no overscroll effect:
 * `overscrollEffect = null` on `verticalScroll` and `LazyColumn`).
 *
 * On a OnePlus 7 Pro a plain sheet moved up and down, past the top of the screen, whenever its
 * content was scrolled to the end: the sheet's own drag and the content's scroll fought over the
 * gesture. Here the sheet ignores drags. A pull down with the content at its top moves it with the
 * finger and closes it when far or fast enough, otherwise it springs back. Its height comes from
 * the window, below the status bar and above the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullDownSheet(
    onDismiss: () -> Unit,
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    /** Null when the content draws its own handle. */
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The sheet adds the navigation bar's height under its content: leave room for both bars, or a long
    // sheet (a large font, a long folder name) grows past the screen and its last button hides behind them.
    val screenHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val bars = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val maxHeight = minOf(screenHeight * MAX_HEIGHT_FRACTION, screenHeight - bars)
    val closeDistance = with(density) { CLOSE_DISTANCE.toPx() }
    // How far the sheet is pulled down, following the finger.
    var pull by remember { mutableFloatStateOf(0f) }
    val pullDown = remember(sheetState, closeDistance) {
        pullDownConnection(closeDistance, { pull }, { pull = it }) { launchClose(scope, sheetState, onDismiss) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.graphicsLayer { translationY = pull },
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        properties = properties,
        dragHandle = dragHandle,
    ) {
        Box(modifier = Modifier.heightIn(max = maxHeight).nestedScroll(pullDown)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun launchClose(scope: CoroutineScope, state: SheetState, onDismiss: () -> Unit) {
    scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
}

/**
 * Turns what a scrolling child cannot scroll any further (a pull down at its top) into [pull];
 * a release past [closeDistance], or fast enough, calls [close], otherwise [pull] animates back.
 */
private fun pullDownConnection(
    closeDistance: Float,
    pull: () -> Float,
    setPull: (Float) -> Unit,
    close: () -> Unit,
) = object : NestedScrollConnection {
    // Pushing back up takes the pull back before the content scrolls.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f || pull() <= 0f) return Offset.Zero
        val used = available.y.coerceAtLeast(-pull())
        setPull(pull() + used)
        return Offset(0f, used)
    }

    // What the content can't scroll down any further pulls the sheet.
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
        setPull(pull() + available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (pull() <= 0f) return Velocity.Zero
        if (pull() > closeDistance || available.y > closeDistance * 10) {
            close()
        } else {
            animate(pull(), 0f) { value, _ -> setPull(value) }
        }
        return available
    }
}

private const val MAX_HEIGHT_FRACTION = 0.92f

/** How far a pull down closes the sheet (or a fling at ten times that per second). */
private val CLOSE_DISTANCE = 56.dp
