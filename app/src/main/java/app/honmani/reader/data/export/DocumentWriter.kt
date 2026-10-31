package app.honmani.reader.data.export

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes [text] as UTF-8 (no byte order mark) to a document the reader picked, [uri] a content URI. */
suspend fun writeTextDocument(context: Context, uri: String, text: String) {
    withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(Uri.parse(uri), "wt")
            ?: throw IOException("Cannot write $uri")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
