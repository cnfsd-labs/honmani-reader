package app.honmani.reader.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A folder with plain files, and the failures a document provider can show. */
internal class FakeFolder : FolderTarget {
    val files = mutableMapOf<String, ByteArray>()
    val granted = mutableSetOf<String>()
    var denyGrant = false
    var denyWrite = false
    var folderGone = false

    /** A cloud folder still fetching its listing: whether a file is there can't be told yet. */
    var loading = false

    override fun grant(folder: String) {
        if (denyGrant) throw SecurityException("no")
        granted += folder
    }

    override fun release(folder: String) {
        granted -= folder
    }

    override fun find(folder: String, name: String): String? {
        if (folderGone) throw FileNotFoundException(folder)
        if (loading) throw IOException("still loading")
        return "$folder/$name".takeIf { it in files }
    }

    override fun create(folder: String, name: String): String {
        if (folderGone) throw FileNotFoundException(folder)
        // As document providers do, a taken name gets a number.
        val address = generateSequence(1) { it + 1 }
            .map { if (it == 1) "$folder/$name" else "$folder/${name.removeSuffix(".zip")} ($it).zip" }
            .first { it !in files }
        files[address] = ByteArray(0)
        return address
    }

    /** Fails after [failAfter] bytes, as a card pulled out half way would. */
    var failAfter: Int? = null

    /** When set, only the stream that makes [opened] reach [failOnOpen] fails, not every one. */
    var failOnOpen: Int? = null
    var opened = 0

    override fun open(file: String): OutputStream {
        if (denyWrite) throw SecurityException("permission lost")
        if (file !in files) throw FileNotFoundException(file)
        opened++
        val failing = failOnOpen == null || failOnOpen == opened
        val bytes = ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) {
                if (failing && failAfter != null && bytes.size() >= failAfter!!) throw IOException("gone")
                bytes.write(b)
            }

            override fun close() {
                files[file] = bytes.toByteArray()
            }
        }
    }

    override fun delete(file: String): Boolean {
        files.remove(file)
        return true
    }

    /** When each file was last changed, for [list]; a test bumps it as a folder app would. */
    val modified = mutableMapOf<String, Long>()

    override fun list(folder: String): List<FolderFile> {
        if (folderGone) throw FileNotFoundException(folder)
        return files.filterKeys { it.substringBeforeLast('/') == folder }
            .map { (address, bytes) -> FolderFile(address, address.substringAfterLast('/'), bytes.size.toLong(), modified[address] ?: 0) }
    }

    override fun read(file: String): InputStream = files[file]?.inputStream() ?: throw FileNotFoundException(file)

    var renamable = true

    override fun canRename(file: String) = renamable

    /** The rename happens, then the process "dies" before anything after it runs. */
    var dieAfterRename = false

    override fun rename(file: String, name: String): String {
        val address = file.substringBeforeLast('/') + "/" + name
        if (address in files) throw IOException("exists")
        files[address] = files.remove(file)!!
        if (dieAfterRename) throw IOException("killed")
        return address
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
        assertEquals(FolderBackupState.On(9_000L), backup.state.value)
    }

    @Test
    fun aFailedWriteLeavesTheEarlierBackupAndNoTemporaryFile() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)

        source.writeText("zip two, longer")
        folder.failAfter = 3
        backup.copy(source)

        assertEquals(FolderBackupState.Unavailable, backup.state.value)
        assertEquals(mapOf(file to "zip one"), folder.files.mapValues { String(it.value) })
    }

    @Test
    fun aProviderThatCannotRenameIsOverwrittenInPlaceAndLeavesNothingBehind() = runBlocking {
        folder.renamable = false
        backup.choose("content://tree/a")
        backup.copy(source)
        source.writeText("zip two")
        backup.copy(source)

        assertEquals(mapOf(file to "zip two"), folder.files.mapValues { String(it.value) })
        assertEquals(FolderBackupState.On(5_000L), backup.state.value)
    }

    @Test
    fun anOverwriteInPlaceThatDiesHalfWayKeepsTheFinishedTemporaryFile() = runBlocking {
        folder.renamable = false
        backup.choose("content://tree/a")
        backup.copy(source)
        source.writeText("zip two, longer")
        // The next stream is the temporary file of the second copy, the one after the overwrite.
        folder.failOnOpen = folder.opened + 2
        folder.failAfter = 3
        backup.copy(source)

        assertEquals(FolderBackupState.Unavailable, backup.state.value)
        assertTrue(folder.files.values.any { String(it) == "zip two, longer" })
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

    private fun backupBytes(exportedAt: Long): ByteArray =
        ByteArrayOutputStream().also { BackupCodec.write(BackupFile(exportedAt = exportedAt), it) }.toByteArray()

    private fun stamp(at: Long) = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(at))

    @Test
    fun aBackupAnotherInstallLeftIsKeptUnderADatedNameAndOffered() = runBlocking {
        val made = 1_794_580_735_235L
        val earlier = backupBytes(made)
        folder.files[file] = earlier
        backup.choose("content://tree/a")

        backup.copy(source)

        val kept = "content://tree/a/honmani-backup-${stamp(made)}.zip"
        assertTrue(earlier.contentEquals(folder.files[kept]))
        assertEquals("zip one", String(folder.files[file]!!))
        assertEquals(FoundBackup(kept, "honmani-backup-${stamp(made)}.zip", made), backup.found.value)
        assertEquals(made, backup.read(backup.found.value!!).exportedAt)
        // Later copies replace this install's own file.
        source.writeText("zip two")
        backup.copy(source)
        assertEquals(setOf(file, kept), folder.files.keys)
        assertEquals("zip two", String(folder.files[file]!!))
    }

    @Test
    fun theOfferSurvivesARestartUntilDismissed() = runBlocking {
        folder.files[file] = backupBytes(1_000L)
        backup.choose("content://tree/a")
        backup.copy(source)

        val again = FolderBackup(device, folder)
        assertEquals(backup.found.value, again.found.value)
        again.dismissFound()
        assertNull(again.found.value)
        assertNull(FolderBackup(device, folder).found.value)
    }

    @Test
    fun aCopyInterruptedRightAfterItLandedIsStillKnownAsOwn() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)

        source.writeText("zip two")
        folder.dieAfterRename = true
        backup.copy(source)
        assertEquals("zip two", String(folder.files[file]!!))

        folder.dieAfterRename = false
        val restarted = FolderBackup(device, folder, now = { clock })
        source.writeText("zip three")
        restarted.copy(source)

        assertEquals(mapOf(file to "zip three"), folder.files.mapValues { String(it.value) })
        assertNull(restarted.found.value)
    }

    @Test
    fun thisInstallsOwnCopyIsReplacedWhenTheFolderIsChosenAgain() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)
        backup.disable()

        backup.choose("content://tree/a")
        source.writeText("zip two")
        backup.copy(source)

        assertEquals(mapOf(file to "zip two"), folder.files.mapValues { String(it.value) })
        assertNull(backup.found.value)
    }

    @Test
    fun aProviderThatCannotRenameKeepsACopy() = runBlocking {
        folder.renamable = false
        val earlier = backupBytes(1_000L)
        folder.files[file] = earlier
        backup.choose("content://tree/a")

        backup.copy(source)

        assertTrue(earlier.contentEquals(folder.files["content://tree/a/honmani-backup-${stamp(1_000L)}.zip"]))
        assertEquals("zip one", String(folder.files[file]!!))
    }

    @Test
    fun aFileThatIsNoBackupIsKeptButNotOffered() = runBlocking {
        clock = 2_000L
        folder.files[file] = "not a zip".toByteArray()
        backup.choose("content://tree/a")

        backup.copy(source)

        assertEquals("not a zip", String(folder.files["content://tree/a/honmani-backup-${stamp(2_000L)}.zip"]!!))
        assertNull(backup.found.value)
    }

    @Test
    fun aDatedNameAlreadyTakenGetsANumber() = runBlocking {
        folder.files["content://tree/a/honmani-backup-${stamp(1_000L)}.zip"] = "older".toByteArray()
        folder.files[file] = backupBytes(1_000L)
        backup.choose("content://tree/a")

        backup.copy(source)

        assertEquals("honmani-backup-${stamp(1_000L)}-2.zip", backup.found.value?.name)
        assertEquals("older", String(folder.files["content://tree/a/honmani-backup-${stamp(1_000L)}.zip"]!!))
    }

    @Test
    fun aBackupThatCannotBeKeptIsNotOverwritten() = runBlocking {
        val earlier = backupBytes(1_000L)
        folder.files[file] = earlier
        backup.choose("content://tree/a")
        folder.renamable = false
        folder.denyWrite = true

        backup.copy(source)

        assertEquals(FolderBackupState.Unavailable, backup.state.value)
        assertTrue(earlier.contentEquals(folder.files[file]))
    }

    @Test
    fun anotherPhonesBackupWrittenLaterIsKeptNotOverwritten() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)
        // Another phone sharing the synced folder writes its own backup there.
        val other = backupBytes(1_000L)
        folder.files[file] = other

        source.writeText("zip two")
        backup.copy(source)

        val kept = "content://tree/a/honmani-backup-${stamp(1_000L)}.zip"
        assertTrue(other.contentEquals(folder.files[kept]))
        assertEquals("zip two", String(folder.files[file]!!))
        assertEquals(kept, backup.found.value?.file)
    }

    @Test
    fun aFolderThatCannotBeListedForSureIsNotWrittenAndLookedAtAgain() = runBlocking {
        val other = backupBytes(1_000L)
        folder.files[file] = other
        backup.choose("content://tree/a")
        folder.loading = true

        backup.copy(source)

        assertEquals(FolderBackupState.Unavailable, backup.state.value)
        assertEquals(setOf(file), folder.files.keys)
        assertTrue(other.contentEquals(folder.files[file]))

        folder.loading = false
        backup.copy(source)

        assertTrue(other.contentEquals(folder.files["content://tree/a/honmani-backup-${stamp(1_000L)}.zip"]))
        assertEquals("zip one", String(folder.files[file]!!))
        assertEquals(FolderBackupState.On(5_000L), backup.state.value)
    }

    @Test
    fun aLeftoverTemporaryFileSurvivesAFailedWrite() = runBlocking {
        backup.choose("content://tree/a")
        backup.copy(source)
        val leftover = "content://tree/a/tmp-${FolderBackup.FILE_NAME}"
        folder.files[leftover] = "only finished copy".toByteArray()

        source.writeText("zip two, longer")
        folder.failAfter = 3
        backup.copy(source)

        assertEquals(FolderBackupState.Unavailable, backup.state.value)
        assertEquals("only finished copy", String(folder.files[leftover]!!))

        folder.failAfter = null
        backup.copy(source)

        assertEquals(mapOf(file to "zip two, longer"), folder.files.mapValues { String(it.value) })
    }

    @Test
    fun twoPhonesOnOneFolderLeaveOnlyTheNewestDatedCopyOfEachOther() = runBlocking {
        val otherDevice = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("folder-backup-test-other", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val other = FolderBackup(otherDevice, folder, now = { clock })
        val otherSource: File = Files.createTempFile("other", ".zip").toFile()
        // A file the reader put there under a name like a kept one is never touched.
        val own = "content://tree/a/honmani-backup-2020-01-01-0000.zip"
        folder.files[own] = "the reader's".toByteArray()
        backup.choose("content://tree/a")
        other.choose("content://tree/a")
        val minute = 60_000L
        val times = (1..4).map { 1_794_580_735_235L + it * 10 * minute }
        for ((round, at) in times.withIndex()) {
            source.writeBytes(backupBytes(at))
            backup.copy(source)
            otherSource.writeBytes(backupBytes(at + 5 * minute))
            other.copy(otherSource)
            // Each phone answers its first offer; later backups of the other phone aren't offered.
            if (round == 0) {
                backup.dismissFound()
                other.dismissFound()
            }
        }

        // This phone kept the other's backup from the third round; the other phone kept this
        // phone's last one; the other phone wrote last.
        val keptOfOther = "content://tree/a/honmani-backup-${stamp(times[2] + 5 * minute)}.zip"
        val keptOfThis = "content://tree/a/honmani-backup-${stamp(times[3])}.zip"
        assertEquals(setOf(file, own, keptOfOther, keptOfThis), folder.files.keys)
        assertEquals(times[3] + 5 * minute, BackupCodec.read(folder.files[file]!!.inputStream()).exportedAt)
        assertNull(backup.found.value)
        assertNull(other.found.value)
    }

    @Test
    fun anUnansweredOfferMovesToTheNewerCopy() = runBlocking {
        backup.choose("content://tree/a")
        folder.files[file] = backupBytes(1_000L)
        backup.copy(source)
        folder.files[file] = backupBytes(1_000_000L)
        source.writeText("zip two")
        backup.copy(source)

        val newer = "content://tree/a/honmani-backup-${stamp(1_000_000L)}.zip"
        assertEquals(setOf(file, newer), folder.files.keys)
        assertEquals(newer, backup.found.value?.file)
    }

    @Test
    fun choosingTheFolderAgainOffersAgain() = runBlocking {
        backup.choose("content://tree/a")
        folder.files[file] = backupBytes(1_000L)
        backup.copy(source)
        backup.dismissFound()
        folder.files[file] = backupBytes(1_000_000L)
        backup.copy(source)
        assertNull(backup.found.value)

        backup.choose("content://tree/a")
        folder.files[file] = backupBytes(2_000_000L)
        backup.copy(source)
        assertEquals(2_000_000L, backup.found.value?.exportedAt)
    }
}
