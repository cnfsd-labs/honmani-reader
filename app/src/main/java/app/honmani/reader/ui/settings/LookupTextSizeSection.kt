package app.honmani.reader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R
import app.honmani.reader.data.settings.AppearanceSettings
import kotlin.math.roundToInt

/** Settings → Reading: the size of the lookup panel's text, 85% to 150% in steps of 5 (100% by default). */
@Composable
fun LookupTextSizeSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.appearanceSettings }
    val percent by settings.lookupTextPercent.collectAsStateWithLifecycle()
    val min = AppearanceSettings.LOOKUP_TEXT_MIN
    val max = AppearanceSettings.LOOKUP_TEXT_MAX
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_lookup_text_size, percent)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_lookup_text_size_details))
                Slider(
                    value = percent.toFloat(),
                    onValueChange = { settings.setLookupTextPercent(it.roundToInt()) },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (max - min) / AppearanceSettings.LOOKUP_TEXT_STEP - 1,
                )
            }
        },
    )
}
