package app.honmani.reader.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.honmani.reader.R

/**
 * A book's difficulty (1–100) as a small rounded label, for a cover corner or a list row.
 * [description] is what a screen reader says instead of the bare number.
 */
@Composable
fun DifficultyBadge(
    difficulty: Int,
    modifier: Modifier = Modifier,
    description: String = stringResource(R.string.book_difficulty, difficulty),
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Text(
            text = "$difficulty",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clearAndSetSemantics { contentDescription = description },
        )
    }
}
