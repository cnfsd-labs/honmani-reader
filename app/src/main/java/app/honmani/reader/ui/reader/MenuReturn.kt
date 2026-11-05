package app.honmani.reader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/*
 * Sub-screens of the reader menu (bookmarks, highlights, ...): when one was opened from the menu,
 * system Back returns to the menu instead of the book. A tap outside and a swipe down still close
 * to the book. A sheet or dialog gets a non-null `onBack` only in that case; with null it behaves
 * as before.
 */

/** Sheet properties: the sheet leaves Back to [onBack] when there is one. */
@OptIn(ExperimentalMaterial3Api::class)
fun menuReturnSheetProperties(onBack: (() -> Unit)?): ModalBottomSheetProperties =
    ModalBottomSheetProperties(shouldDismissOnBackPress = onBack == null)

/** Dialog properties: the dialog leaves Back to [onBack] when there is one. */
fun menuReturnDialogProperties(onBack: (() -> Unit)?): DialogProperties =
    DialogProperties(dismissOnBackPress = onBack == null)

/** Calls from inside the sheet's or dialog's own window, which has its own back dispatcher. */
@Composable
fun MenuReturnBack(onBack: (() -> Unit)?) {
    if (onBack != null) BackHandler(onBack = onBack)
}
