package app.honmani.reader.data.export

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes [text] as UTF-8 to `cache/[folder]/[name]` (the folder is emptied first, so nothing
 * stays behind) and returns the content URI the file provider shares it under. [folder] must be
 * in `res/xml/file_paths.xml`.
 */
suspend fun writeShareFile(context: Context, folder: String, name: String, text: String): String =
    withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, folder).apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(directory, name).apply { writeText(text, Charsets.UTF_8) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
    }
