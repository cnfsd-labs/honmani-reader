package app.honmani.reader.ui.reader

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureViewerTest {
    private val bytes = byteArrayOf(1, 2, 3, 4, 5, -1, -2)
    private val encoded = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `a base64 data url of a picture type is decoded`() {
        assertArrayEquals(bytes, PictureData.decode("data:image/png;base64,$encoded"))
        assertArrayEquals(bytes, PictureData.decode("data:image/jpeg;base64,$encoded"))
        assertArrayEquals(bytes, PictureData.decode("data:image/webp;base64,$encoded"))
        assertArrayEquals(bytes, PictureData.decode("data:image/gif;base64,$encoded"))
    }

    @Test
    fun `other data urls are refused`() {
        assertNull(PictureData.decode("data:image/svg+xml;base64,$encoded"))
        assertNull(PictureData.decode("data:text/html;base64,$encoded"))
        assertNull(PictureData.decode("data:image/png,$encoded"))
        assertNull(PictureData.decode("data:image/png;base64,"))
        assertNull(PictureData.decode("data:image/png;base64,%%%"))
        assertNull(PictureData.decode("https://example.com/a.png"))
        assertNull(PictureData.decode("data:image/png;base64"))
    }

    @Test
    fun `an image message carries the picture bytes`() {
        val message = BridgeMessage.parse("""{"type":"image","data":"data:image/png;base64,$encoded"}""")
        assertArrayEquals(bytes, (message as BridgeMessage.Image).bytes)
        assertNull(BridgeMessage.parse("""{"type":"image","data":"data:text/html;base64,$encoded"}"""))
        assertNull(BridgeMessage.parse("""{"type":"image"}"""))
        assertNull(BridgeMessage.parse("""{"type":"image","data":5}"""))
    }

    @Test
    fun `a picture may be longer than other messages, but only up to its cap`() {
        val big = Base64.getEncoder().encodeToString(ByteArray(1_500_000) { it.toByte() })
        assertNotNull(BridgeMessage.parse("""{"type":"image","data":"data:image/jpeg;base64,$big"}"""))
        val tooBig = "A".repeat(3_200_000)
        assertNull(BridgeMessage.parse("""{"type":"image","data":"data:image/jpeg;base64,$tooBig"}"""))
        // A long message of another type stays refused.
        val long = "a".repeat(1_500_000)
        assertNull(BridgeMessage.parse("""{"type":"lookup","text":"$long"}"""))
        assertNull(BridgeMessage.parse("""{"type":"dismiss","pad":"$long"}"""))
    }

    private val view = 1000f to 2000f

    @Test
    fun `a picture is fitted into the screen without cropping`() {
        assertEquals(1000f to 500f, Zoom.fit(2000f, 1000f, view.first, view.second))
        assertEquals(500f to 2000f, Zoom.fit(100f, 400f, view.first, view.second))
        assertEquals(0f to 0f, Zoom.fit(0f, 10f, 100f, 100f))
    }

    @Test
    fun `a fitted picture cannot be moved, a zoomed one only to its edges`() {
        assertEquals(0f, Zoom.maxPan(500f, 1f, 1000f), 0f)
        assertEquals(0f, Zoom.maxPan(1000f, 1f, 1000f), 0f)
        assertEquals(1000f, Zoom.maxPan(1000f, 3f, 1000f), 0f)
    }

    private fun Zoom.pinch(factor: Float, x: Float = 0f, y: Float = 0f, panX: Float = 0f, panY: Float = 0f) =
        transformed(factor, x, y, panX, panY, view.first, view.second, 1000f, 500f)

    @Test
    fun `zoom stays between fit and the maximum`() {
        assertEquals(1f, Zoom().pinch(0.2f).scale, 0f)
        assertEquals(Zoom.MAX_SCALE, Zoom().pinch(100f).scale, 0f)
        assertEquals(2f, Zoom().pinch(2f).scale, 0f)
    }

    @Test
    fun `a picture at fit does not move in the direction it fills`() {
        val moved = Zoom().pinch(1f, panX = 300f, panY = 300f)
        assertEquals(0f, moved.offsetX, 0f)
        // 500 high in a 2000 high screen: no room to move up or down at fit.
        assertEquals(0f, moved.offsetY, 0f)
    }

    @Test
    fun `pinching keeps the point under the fingers in place`() {
        // 3x around a point 100 px right of the centre: the picture moves 200 px left.
        val zoomed = Zoom().pinch(3f, x = 100f)
        assertEquals(3f, zoomed.scale, 0f)
        assertEquals(-200f, zoomed.offsetX, 0.001f)
    }

    @Test
    fun `dragging is clamped at the picture's edges`() {
        val zoomed = Zoom().pinch(3f)
        // 1000 px wide fitted, 3x = 3000 px in a 1000 px screen: 1000 px of play each way.
        assertEquals(1000f, zoomed.pinch(1f, panX = 5000f).offsetX, 0f)
        assertEquals(-1000f, zoomed.pinch(1f, panX = -5000f).offsetX, 0f)
        // 500 px high fitted, 3x = 1500 px in a 2000 px screen: still no vertical play.
        assertEquals(0f, zoomed.pinch(1f, panY = 700f).offsetY, 0f)
    }

    @Test
    fun `zooming out shrinks the offset back inside the edges`() {
        val zoomed = Zoom().pinch(3f).pinch(1f, panX = 1000f)
        assertEquals(1000f, zoomed.offsetX, 0f)
        assertEquals(0f, zoomed.pinch(0.01f).offsetX, 0f)
    }

    @Test
    fun `a double tap zooms in at the tap, and a second one goes back to fit`() {
        val zoomed = Zoom().doubleTapped(100f, 0f, view.first, view.second, 1000f, 500f)
        assertEquals(Zoom.DOUBLE_TAP_SCALE, zoomed.scale, 0f)
        assertTrue(zoomed.offsetX < 0f)
        assertEquals(Zoom(), zoomed.doubleTapped(0f, 0f, view.first, view.second, 1000f, 500f))
    }
}
