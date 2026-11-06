package app.honmani.reader.data.library

import app.honmani.reader.data.library.FakeDocumentTree.Companion.file
import app.honmani.reader.data.library.FakeDocumentTree.Companion.folder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeWalkerTest {
    private fun ids(scan: TreeScan) = scan.files.map { it.id }

    @Test
    fun findsBooksInAllSubfoldersAndSkipsOtherFiles() = runBlocking {
        val tree = FakeDocumentTree(
            mapOf(
                "root" to listOf(file("root/a.epub"), file("root/notes.pdf"), folder("root/s1"), folder("root/empty")),
                "root/s1" to listOf(file("root/s1/B.EPUB"), folder("root/s1/deep"), file("root/s1/c.txt")),
                "root/s1/deep" to listOf(file("root/s1/deep/d.epub")),
                "root/empty" to emptyList(),
            ),
        )

        val scan = TreeWalker.findBooks(tree)

        assertEquals(listOf("root/a.epub", "root/s1/B.EPUB", "root/s1/c.txt", "root/s1/deep/d.epub"), ids(scan))
        assertEquals(0, scan.unreadableFolders)
        assertFalse(scan.incomplete)
    }

    @Test
    fun aFolderThatLeadsBackToItselfEnds() = runBlocking {
        val tree = FakeDocumentTree(
            mapOf(
                "root" to listOf(file("root/a.epub"), folder("loop")),
                "loop" to listOf(folder("root"), folder("loop"), file("loop/b.epub")),
            ),
        )

        val scan = TreeWalker.findBooks(tree)

        assertEquals(listOf("root/a.epub", "loop/b.epub"), ids(scan))
        assertEquals(listOf("root", "loop"), tree.listed)
    }

    @Test
    fun foldersBelowTheDepthLimitAreLeftOutAndReported() = runBlocking {
        val tree = FakeDocumentTree(
            mapOf(
                "root" to listOf(file("root/a.epub"), folder("d1")),
                "d1" to listOf(file("d1/b.epub"), folder("d2")),
                "d2" to listOf(file("d2/c.epub")),
            ),
        )

        val scan = TreeWalker.findBooks(tree, maxDepth = 1)

        assertEquals(listOf("root/a.epub", "d1/b.epub"), ids(scan))
        assertTrue(scan.incomplete)
    }

    @Test
    fun theFileLimitStopsTheSearch() = runBlocking {
        val tree = FakeDocumentTree(mapOf("root" to (1..5).map { file("root/b$it.epub") }))

        val scan = TreeWalker.findBooks(tree, maxFiles = 3)

        assertEquals(3, scan.files.size)
        assertTrue(scan.incomplete)
    }

    @Test
    fun anUnreadableFolderIsCountedAndTheRestIsStillSearched() = runBlocking {
        val tree = FakeDocumentTree(
            mapOf(
                "root" to listOf(folder("secret"), file("root/a.epub")),
            ),
        )

        val scan = TreeWalker.findBooks(tree)

        assertEquals(listOf("root/a.epub"), ids(scan))
        assertEquals(1, scan.unreadableFolders)
    }

    @Test
    fun theSameFileSeenTwiceCountsOnce() = runBlocking {
        val tree = FakeDocumentTree(
            mapOf(
                "root" to listOf(file("x/a.epub"), folder("f")),
                "f" to listOf(file("x/a.epub")),
            ),
        )

        assertEquals(listOf("x/a.epub"), ids(TreeWalker.findBooks(tree)))
    }

    @Test
    fun bookNamesAreEpubAndTextFiles() {
        assertTrue(TreeWalker.isBookName("a.epub"))
        assertTrue(TreeWalker.isBookName("b.TXT"))
        assertFalse(TreeWalker.isBookName("c.pdf"))
        assertFalse(TreeWalker.isBookName("epub"))
    }
}
