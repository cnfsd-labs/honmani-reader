package app.honmani.reader.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honmani.reader.HonmaniApplication
import app.honmani.reader.R

/** Settings → Appearance: the switch for the interface's animations (on by default). */
@Composable
fun AnimationsSection() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as HonmaniApplication).container.appearanceSettings }
    val on by settings.animations.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_animations)) },
        supportingContent = { Text(stringResource(R.string.settings_animations_details)) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.toggleable(on, role = Role.Switch, onValueChange = settings::setAnimations),
    )
}
