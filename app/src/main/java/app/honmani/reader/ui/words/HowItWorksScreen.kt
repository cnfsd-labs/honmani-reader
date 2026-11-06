package app.honmani.reader.ui.words

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.ui.HonmaniTopAppBar

/** The words explained in plain language: from the menu of the Words page. */
@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { HonmaniTopAppBar(title = stringResource(R.string.how_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Section(R.string.how_saved_title, R.string.how_saved_body)
            Section(R.string.how_steps_title, R.string.how_steps_body)
            Section(R.string.how_marks_title, R.string.how_marks_body)
            Section(R.string.how_review_title, R.string.how_review_body)
            Section(R.string.how_starred_title, R.string.how_starred_body)
            Section(R.string.how_ignored_title, R.string.how_ignored_body)
        }
    }
}

@Composable
private fun Section(title: Int, body: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
}
