package app.honmani.reader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.honmani.reader.R
import app.honmani.reader.data.opds.OpdsCatalog

/** Where to add a book from: files on the phone, or one of the user's book catalogues. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookSheet(
    catalogs: List<OpdsCatalog>,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onExport: () -> Unit,
    onOpenCatalog: (OpdsCatalog) -> Unit,
    onEditCatalog: (OpdsCatalog) -> Unit,
    onAddCatalog: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.add_book_files)) },
                supportingContent = { Text(stringResource(R.string.add_book_files_details)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onPickFiles),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.add_book_folder)) },
                supportingContent = { Text(stringResource(R.string.add_book_folder_details)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onPickFolder),
            )
            for (catalog in catalogs) {
                ListItem(
                    leadingContent = { Icon(painterResource(R.drawable.ic_cloud), contentDescription = null) },
                    headlineContent = { Text(catalog.title) },
                    supportingContent = { Text(catalog.url.substringAfter("://")) },
                    trailingContent = {
                        IconButton(onClick = { onEditCatalog(catalog) }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.catalog_edit_title),
                            )
                        }
                    },
                    colors = colors,
                    modifier = Modifier.clickable { onOpenCatalog(catalog) },
                )
            }
            ListItem(
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.catalog_add_title)) },
                supportingContent = { Text(stringResource(R.string.add_book_catalog_details)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onAddCatalog),
            )
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.library_export)) },
                supportingContent = { Text(stringResource(R.string.library_export_details)) },
                colors = colors,
                modifier = Modifier.clickable(onClick = onExport),
            )
        }
    }
}
