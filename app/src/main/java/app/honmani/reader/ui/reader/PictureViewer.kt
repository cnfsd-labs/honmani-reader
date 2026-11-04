package app.honmani.reader.ui.reader

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A picture from the book over the whole reader (a tap on it, `tap-bridge.js`): pinch to zoom,
 * double tap to zoom in or back, drag to move it. Back or the close button return to the page.
 */
@Composable
internal fun PictureViewer(bytes: ByteArray, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Composed after the lookup panel's handler, so back closes the picture first.
    BackHandler(onBack = onClose)
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) { decodePicture(bytes) }
    }
    var zoom by remember(bytes) { mutableStateOf(Zoom()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }) {
        val image = bitmap
        if (image != null && size.width > 0 && size.height > 0) {
            val viewWidth = size.width.toFloat()
            val viewHeight = size.height.toFloat()
            val (contentWidth, contentHeight) = Zoom.fit(image.width.toFloat(), image.height.toFloat(), viewWidth, viewHeight)
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.picture_viewer_picture),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    // Before graphicsLayer, so the touches are in screen coordinates.
                    .pointerInput(image, size) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                zoom = zoom.doubleTapped(
                                    tap.x - viewWidth / 2f, tap.y - viewHeight / 2f,
                                    viewWidth, viewHeight, contentWidth, contentHeight,
                                )
                            },
                        )
                    }
                    .pointerInput(image, size) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            zoom = zoom.transformed(
                                zoomChange, centroid.x - viewWidth / 2f, centroid.y - viewHeight / 2f,
                                pan.x, pan.y, viewWidth, viewHeight, contentWidth, contentHeight,
                            )
                        }
                    }
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offsetX
                        translationY = zoom.offsetY
                    },
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(readerInsets)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.picture_viewer_close), tint = Color.White)
        }
    }
}

/** The longest side the viewer draws: bigger pictures are read at a lower resolution. */
private const val MAX_SIDE = 4096

private fun decodePicture(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
