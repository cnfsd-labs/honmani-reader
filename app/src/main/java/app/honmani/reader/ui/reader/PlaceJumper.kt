package app.honmani.reader.ui.reader

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import app.honmani.reader.data.library.BookText
import app.honmani.reader.data.sync.ProgressSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Jumps in the open book (the slider, a bookmark, a search hit) the way the in-book search
 * always opened a hit: ttu's character count becomes its saved position and the page reloads
 * ([TtuLibrary.openHit]). Each jump offers "Back to where you were" in [snackbar], and tells
 * [sync] that ttu's first save after the reload is a look, not reading (see [ProgressSync.onJump]).
 */
class PlaceJumper(
    private val ttu: TtuLibrary,
    private val sync: ProgressSync,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) {
    /**
     * Opens the book at [characters] of [total]; the text next to that place ([before] it and the
     * [match] after it, may be blank) is how the page finds the spot to show. [text], if known,
     * names the place the reader jumps from.
     */
    fun jump(
        ttuId: Long,
        characters: Long,
        total: Long,
        before: String,
        match: String,
        text: BookText?,
        message: String,
        backLabel: String,
    ) {
        scope.launch {
            val from = try {
                ttu.bookmark(ttuId).first
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: 0L
            val fromSpot = text?.aroundPageStart(from.toInt())
            try {
                sync.onJump(ttuId, characters)
                ttu.openHit(ttuId, characters, fraction(characters, total), before, match)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val result = snackbar.showSnackbar(message, backLabel, duration = SnackbarDuration.Long)
                if (result != SnackbarResult.ActionPerformed) return@launch
                // The place the reader came from is a place read before: it is saved like a page turn.
                try {
                    ttu.openHit(ttuId, from, fraction(from, total), fromSpot?.hint.orEmpty(), fromSpot?.match.orEmpty())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The reader is gone (the book was closed meanwhile).
                }
            }
        }
    }

    private fun fraction(characters: Long, total: Long) = if (total <= 0) 0.0 else (characters.toDouble() / total).coerceIn(0.0, 1.0)
}
