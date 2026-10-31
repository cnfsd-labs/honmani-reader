package app.honmani.reader.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A folder with plain files, and the failures a document provider can show. */
private class FakeFolder : FolderTarget {
    val files = mutableMapOf<String, ByteArray>()
    val granted = mutableSetOf<String>()
    var created = 0
    var denyGrant = false
    var denyWrite = false
    var folderGone = false

    override fun grant(folder: String) {
        if (denyGrant) throw SecurityException("no")
        granted += folder
    }

    override fun release(folder: String) {
        granted -= folder
    }

    override fun findOrCreate(folder: String, name: String): String {
        if (folderGone) throw FileNotFoundException(folder)
        val address = "$folder/$name"
        if (address !in files) {
            files[address] = ByteArray(0)
            created++
        }
        return address
    }

    override fun open(file: String): OutputStream {
        if (denyWrite) throw SecurityException("permission lost")
        if (file !in files) throw FileNotFoundException(file)
        val bytes = ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = bytes.write(b)

            override fun close() {
                files[file] = bytes.toByteArray()
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class FolderBackupTest {
    private val device = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("folder-backup-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val folder = FakeFolder()
    private var clock = 5_000L
    private val backup = FolderBackup(device, folder, now = { clock })
    private val source: File = Files.createTempFile("backup", ".zip").toFile().also { it.writeText("zip one") }
    private val file = "content://tree/a/${FolderBackup.FILE_NAME}"

    @Before
    fun startsOff() {
        assertEquals(FolderBackupState.Off, backup.state.value)
    }

    @Test
    fun offByDefaultAndCopiesNothing() = runBlocking {
        backup.copy(source)

        assertTrue(folder.files.isEmpty())
        assertEquals(FolderBackupState.Off, backup.state.value)
    }

    @Test
    fun aChosenFolderGetsOneFileThatIsOverwritten() = runBlocking {
        assertTrue(backup.choose("content://tree/a"))
        assertEquals(FolderBackupState.On(null), backup.state.value)

        backup.copy(source)
        assertEquals("zip one", String(folder.files[file]!!))
        assertEquals(FolderBackupState.On(5_000L), backup.state.value)

        clock = 9_000L
        source.writeText("zip two")
        backup.copy(source)

        assertEquals(mapOf(file to "zip two"), folder.files.mapValues { String(it.value) })
        assertEquals(1, folder.created)
        assertEquals(FolderBackupState.On(9_000L), backup.state.value)
    }

    @Test
    fun theChoiceSurvivesARestart() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)

        assertEquals(FolderBackupState.On(5_000L), FolderBackup(device, folder).state.value)
    }

    @Test
    fun aFileTheReaderDeletedIsMadeAgain() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)
        folder.files.clear()

        clock = 7_000L
        backup.copy(source)

        assertEquals("zip one", String(folder.files[file]!!))
        assertEquals(2, folder.created)
        assertEquals(FolderBackupState.On(7_000L), backup.state.value)
    }

    @Test
    fun aLostPermissionOnlyShowsAsUnavailableUntilChosenAgain() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)
        folder.denyWrite = true

        backup.copy(source)
        assertEquals(FolderBackupState.Unavailable, backup.state.value)
        assertEquals(FolderBackupState.Unavailable, FolderBackup(device, folder).state.value)

        folder.denyWrite = false
        assertTrue(backup.choose("content://tree/a"))
        backup.copy(source)
        assertEquals(FolderBackupState.On(5_000L), backup.state.value)
    }

    @Test
    fun aDeletedFolderOrAFailingProviderNeverThrows() = runBlocking {
        backup.choose("content://tree/a")
        folder.folderGone = true

        backup.copy(source)

        assertEquals(FolderBackupState.Unavailable, backup.state.value)
    }

    @Test
    fun aMissingSourceFileIsAFailureNotACrash() = runBlocking {
        backup.choose("content://tree/a")
        source.delete()

        backup.copy(source)

        assertEquals(FolderBackupState.Unavailable, backup.state.value)
    }

    @Test
    fun switchingOffReleasesAndForgetsTheFolder() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)

        backup.disable()

        assertEquals(FolderBackupState.Off, backup.state.value)
        assertFalse("content://tree/a" in folder.granted)
        folder.files.clear()
        backup.copy(source)
        assertTrue(folder.files.isEmpty())
        assertEquals(FolderBackupState.Off, FolderBackup(device, folder).state.value)
    }

    @Test
    fun choosingAnotherFolderReleasesTheOldOne() {
        backup.choose("content://tree/a")
        backup.choose("content://tree/b")

        assertEquals(setOf("content://tree/b"), folder.granted)
    }

    @Test
    fun aFolderTheSystemWontLetUsKeepIsNotSwitchedOn() {
        folder.denyGrant = true

        assertFalse(backup.choose("content://tree/a"))
        assertEquals(FolderBackupState.Off, backup.state.value)
    }
}
