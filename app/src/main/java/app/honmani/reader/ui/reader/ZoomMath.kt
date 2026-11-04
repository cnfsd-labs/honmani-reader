package app.honmani.reader.ui.reader

import kotlin.math.max
import kotlin.math.min

/** The zoom and pan of the picture viewer, in pixels from the centre of the screen. */
internal data class Zoom(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    /**
     * The view after a pinch of [zoomChange] around [centroidX]/[centroidY] (from the screen's
     * centre) and a drag of [panX]/[panY]. The point under the fingers stays under them, the scale
     * stays within [MIN_SCALE] and [MAX_SCALE], and the picture (fitted in the screen as
     * [contentWidth] by [contentHeight], before zooming) never leaves a gap it did not have.
     */
    fun transformed(
        zoomChange: Float,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        viewWidth: Float,
        viewHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): Zoom {
        val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = newScale / scale
        return Zoom(
            newScale,
            ((offsetX - centroidX) * factor + centroidX + panX).clampPan(newScale, contentWidth, viewWidth),
            ((offsetY - centroidY) * factor + centroidY + panY).clampPan(newScale, contentHeight, viewHeight),
        )
    }

    /** A double tap at [tapX]/[tapY] (from the centre): zoomed in goes back to fit, otherwise zooms in there. */
    fun doubleTapped(
        tapX: Float,
        tapY: Float,
        viewWidth: Float,
        viewHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): Zoom =
        if (scale > MIN_SCALE * 1.05f) {
            Zoom()
        } else {
            transformed(DOUBLE_TAP_SCALE, tapX, tapY, 0f, 0f, viewWidth, viewHeight, contentWidth, contentHeight)
        }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 3f

        /** How far a picture of [content] px zoomed by [scale] may move from the centre of [view] px. */
        fun maxPan(content: Float, scale: Float, view: Float): Float = max(0f, (content * scale - view) / 2f)

        /** The size of a [width] by [height] picture fitted into [viewWidth] by [viewHeight] without cropping. */
        fun fit(width: Float, height: Float, viewWidth: Float, viewHeight: Float): Pair<Float, Float> {
            if (width <= 0f || height <= 0f) return 0f to 0f
            val ratio = min(viewWidth / width, viewHeight / height)
            return width * ratio to height * ratio
        }
    }

    private fun Float.clampPan(scale: Float, content: Float, view: Float): Float {
        val limit = maxPan(content, scale, view)
        return coerceIn(-limit, limit)
    }
}
