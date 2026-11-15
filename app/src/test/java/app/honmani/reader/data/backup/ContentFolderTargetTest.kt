package app.honmani.reader.data.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentFolderTargetTest {
    private val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
    private val target = ContentFolderTarget(resolver)
    private val folder = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString()

    @Test
    fun aFolderStillLoadingIsNotListedAsComplete() {
        Robolectric.buildContentProvider(FolderProvider::class.java).create(AUTHORITY).get().loading = true

        assertThrows(IOException::class.java) { target.list(folder) }
    }

    @Test
    fun aLoadedFolderIsListed() {
        Robolectric.buildContentProvider(FolderProvider::class.java).create(AUTHORITY)

        assertEquals(listOf("honmani-sync-a.zip"), target.list(folder).map { it.name })
    }

    class FolderProvider : ContentProvider() {
        var loading = false

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(projection).apply {
                addRow(arrayOf<Any?>("a", "honmani-sync-a.zip", 10L, 1_000L))
                if (loading) extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private companion object {
        const val AUTHORITY = "folders"
    }
}
