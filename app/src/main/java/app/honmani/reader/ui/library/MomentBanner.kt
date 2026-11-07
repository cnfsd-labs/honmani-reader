package app.honmani.reader.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.honmani.reader.ui.theme.LocalMotionEnabled

/**
 * A milestone or level-up, as a card sliding in at the top of the screen. It takes no focus and
 * has no scrim, so the screen below stays usable; the owner hides it after a few seconds. A tap
 * calls [onOpen]; a swipe sideways calls [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentBanner(
    text: String?,
    actionLabel: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionOn = LocalMotionEnabled.current
    // The text stays while the banner slides out.
    var lastText by remember { mutableStateOf(text.orEmpty()) }
    if (text != null) lastText = text
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = if (motionOn) {
            slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { -it } + fadeIn(tween(120))
        } else {
            EnterTransition.None
        },
        exit = if (motionOn) slideOutVertically(tween(180)) { -it } + fadeOut(tween(180)) else ExitTransition.None,
    ) {
        // A fresh swipe state for each banner.
        androidx.compose.runtime.key(text) {
            val swipe = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) onDismiss()
                    true
                },
            )
            SwipeToDismissBox(
                state = swipe,
                backgroundContent = {},
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = actionLabel, onClick = onOpen),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            lastText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
