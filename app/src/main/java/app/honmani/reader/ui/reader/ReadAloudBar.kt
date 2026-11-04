package app.honmani.reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import java.text.DecimalFormat

/**
 * The control bar over the bottom of the reader while the book is followed aloud: play or pause,
 * the sentence before and after, the speed and close. It knows nothing about speech: the caller
 * says whether it [playing], what speed to show and an optional [message] (with an optional
 * [onMessageAction] button), so other readers of the text, such as an audiobook, can use it.
 * Every button has a content description; the row fits 360 dp wide at the largest font size.
 */
@Composable
fun ReadAloudBar(
    playing: Boolean,
    rate: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    messageAction: String? = null,
    onMessageAction: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (message != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (messageAction != null) TextButton(onClick = onMessageAction) { Text(messageAction) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = stringResource(R.string.read_aloud_previous))
                }
                IconButton(onClick = onPlayPause) {
                    if (playing) {
                        Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.read_aloud_pause))
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.read_aloud_play))
                    }
                }
                IconButton(onClick = onNext) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.read_aloud_next))
                }
                val shown = stringResource(R.string.read_aloud_speed_label, RATE_FORMAT.format(rate))
                val described = stringResource(R.string.read_aloud_speed, shown)
                val density = LocalDensity.current
                // A speed label that grows with the font would push the close button off a narrow screen.
                CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_LABEL_FONT_SCALE))) {
                    TextButton(
                        onClick = onSpeed,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 56.dp)
                            .semantics { contentDescription = described },
                    ) {
                        Text(shown, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.read_aloud_close))
                }
            }
        }
    }
}

private val RATE_FORMAT = DecimalFormat("0.##")
private const val MAX_LABEL_FONT_SCALE = 1.3f
