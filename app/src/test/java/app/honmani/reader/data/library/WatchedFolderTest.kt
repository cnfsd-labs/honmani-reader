package app.honmani.reader.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.honmani.reader.data.backup.FolderFile
import app.honmani.reader.data.backup.FolderTarget
import app.honmani.reader.data.library.FakeDocumentTree.Companion.file
import app.honmani.reader.data.library.FakeDocumentTree.Companion.folder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchedFolderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val seenFile get() = File(temp.root, "seen.tsv")
    private val hashed = mutableListOf<String>()

    private fun hash(entry: TreeEntry): String {
        hashed += entry.id
        return "hash-of-${entry.id}"
    }

    private fun scan(tree: FakeDocumentTree, seen: SeenFiles, removed: Set<String> = emptySet()) =
        runBlocking { WatchedFolderScan.find(tree, seen, removed) { hash(it) } }

    private fun tree(vararg entries: TreeEntry) = FakeDocumentTree(
        mapOf("root" to listOf(folder("root/s")) , "root/s" to entries.toList()),
    )

    @Test
    fun aNewFileIsFoundAndAnUnchangedOneIsNotHashedAgain() {
        val seen = SeenFiles(seenFile)
        val tree = tree(file("root/s/a.epub").copy(size = 10, modified = 5), file("root/s/notes.pdf"))

        val first = scan(tree, seen)
        assertEquals(listOf("root/s/a.epub"), first.files.map { it.id })
        first.files.forEach(seen::mark)
        seen.save()

        hashed.clear()
        val second = scan(tree, SeenFiles(seenFile))
        assertTrue(second.files.isEmpty())
        assertTrue("an unchanged file must not be read", hashed.isEmpty())
    }

    @Test
    fun aChangedModifiedTimeOrSizeIsLookedAtAgain() {
        val seen = SeenFiles(seenFile)
        seen.mark(file("root/s/a.epub").copy(size = 10, modified = 5))
        seen.mark(file("root/s/b.epub").copy(size = 10, modified = 5))

        val result = scan(
            tree(
                file("root/s/a.epub").copy(size = 10, modified = 6),
                file("root/s/b.epub").copy(size = 11, modified = 5),
            ),
            seen,
        )

        assertEquals(listOf("root/s/a.epub", "root/s/b.epub"), result.files.map { it.id })
    }

    @Test
    fun aFileWhoseHashWasRemovedIsSkippedAndNotReadAgain() {
        val seen = SeenFiles(seenFile)
        val entry = file("root/s/a.epub").copy(size = 10, modified = 5)
        val tree = tree(entry, file("root/s/b.epub").copy(size = 1, modified = 1))

        val result = scan(tree, seen, removed = setOf("hash-of-root/s/a.epub"))

        assertEquals(listOf("root/s/b.epub"), result.files.map { it.id })
        assertEquals(1, result.removed)
        assertTrue(seen.isUnchanged(entry))
    }

    @Test
    fun aFileModifiedJustNowIsLeftForALaterScan() {
        val seen = SeenFiles(seenFile)
        val copying = file("root/s/a.epub").copy(size = 10, modified = 1_000)
        val settled = file("root/s/b.epub").copy(size = 10, modified = 999)

        val result = runBlocking {
            WatchedFolderScan.find(tree(copying, settled), seen, emptySet(), modifiedBefore = 1_000) { hash(it) }
        }

        assertEquals(listOf("root/s/b.epub"), result.files.map { it.id })
        assertEquals(mapOf("root/s/b.epub" to "hash-of-root/s/b.epub"), result.hashes)
        assertEquals(listOf("root/s/b.epub"), hashed)
        assertTrue("a file still being copied must not count as seen", !seen.isUnchanged(copying))
    }

    @Test
    fun aFileThatCannotBeReadIsTriedAgainNextTime() {
        val seen = SeenFiles(seenFile)
        val tree = tree(file("root/s/a.epub"))

        val result = runBlocking { WatchedFolderScan.find(tree, seen, emptySet()) { null } }

        assertTrue(result.files.isEmpty())
        assertEquals(0, seen.size())
    }

    @Test
    fun theSeenListSurvivesARestartAndClears() {
        val entry = file("root/s/a.epub").copy(size = 3, modified = 4)
        SeenFiles(seenFile).apply { mark(entry); save() }

        val again = SeenFiles(seenFile)
        assertTrue(again.isUnchanged(entry))
        again.clear()
        assertFalse(SeenFiles(seenFile).isUnchanged(entry))
    }

    @Test
    fun sha256IsLowercaseHex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            WatchedFolderScan.sha256("abc".toByteArray().inputStream()),
        )
    }

    private fun watched(target: FolderTarget, permitted: (String) -> Boolean = { true }): WatchedFolder {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("watched-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        return WatchedFolder(preferences, seenFile, target, permitted)
    }

    private val tree1 = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBooks"
    private val tree2 = "content://com.android.externalstorage.documents/tree/primary%3AOther"

    @Test
    fun choosingTakesThePermissionAndStoppingGivesItUpButNothingElse() {
        val target = FakeFolderTarget()
        val watched = watched(target)
        assertEquals(WatchedFolder.State.None, watched.state())

        assertTrue(watched.choose(tree1))
        assertEquals(WatchedFolder.State.Watching("Books"), watched.state())
        watched.seen.mark(file("x"))
        assertTrue(watched.choose(tree2))
        assertEquals(listOf(tree1), target.released)
        assertEquals(0, watched.seen.size())

        watched.stop()
        assertEquals(listOf(tree1, tree2), target.released)
        assertEquals(WatchedFolder.State.None, watched.state())
        assertNull(watched.folder)
    }

    @Test
    fun aRefusedPermissionKeepsTheOldChoice() {
        val target = FakeFolderTarget()
        val watched = watched(target)
        watched.choose(tree1)
        target.refuse = true

        assertFalse(watched.choose(tree2))

        assertEquals(tree1, watched.folder)
    }

    @Test
    fun aMissingPermissionOrAFailedScanIsUnavailable() {
        var permission = true
        val watched = watched(FakeFolderTarget()) { permission }
        watched.choose(tree1)

        permission = false
        assertEquals(WatchedFolder.State.Unavailable("Books"), watched.state())
        assertFalse(watched.isPermitted())

        permission = true
        watched.setProblem(true)
        assertEquals(WatchedFolder.State.Unavailable("Books"), watched.state())
        watched.setProblem(false)
        assertEquals(WatchedFolder.State.Watching("Books"), watched.state())
    }
}

internal class FakeFolderTarget : FolderTarget {
    val granted = mutableListOf<String>()
    val released = mutableListOf<String>()
    var refuse = false

    override fun grant(folder: String) {
        if (refuse) throw SecurityException("no")
        granted += folder
    }

    override fun release(folder: String) {
        released += folder
    }

    override fun find(folder: String, name: String): String? = null

    override fun create(folder: String, name: String): String = throw UnsupportedOperationException()

    override fun open(file: String): OutputStream = throw UnsupportedOperationException()

    override fun delete(file: String): Boolean = true

    override fun list(folder: String): List<FolderFile> = emptyList()

    override fun read(file: String): InputStream = throw UnsupportedOperationException()

    override fun canRename(file: String): Boolean = false

    override fun rename(file: String, name: String): String = file
}
