package app.honmani.reader.ui.onboarding

import app.honmani.reader.data.dictionary.DictionaryCatalog
import app.honmani.reader.data.dictionary.DownloadProgress
import app.honmani.reader.data.dictionary.FailureReason
import app.honmani.reader.data.dictionary.ImportError
import app.honmani.reader.data.dictionary.ImportProgress
import app.honmani.reader.data.dictionary.ImportStatus
import app.honmani.reader.ui.dictionaries.CatalogItem
import app.honmani.reader.ui.dictionaries.DictionariesUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarterPackStateTest {
    private val pack = DictionaryCatalog.starterPack(japanese = false)

    private fun state(installed: Int, imports: List<ImportStatus> = emptyList()) = DictionariesUiState.Loaded(
        dictionaries = emptyList(),
        imports = imports,
        catalog = DictionaryCatalog.dictionaries.map { dictionary ->
            val index = pack.indexOf(dictionary)
            val itemState = if (index in 0 until installed) CatalogItem.State.INSTALLED else CatalogItem.State.AVAILABLE
            CatalogItem(dictionary, itemState)
        },
    )

    @Test
    fun followsTheDownloads() {
        assertEquals(StarterPackState.NotStarted, StarterPackState.of(pack, DictionariesUiState.Loading, started = false))
        assertEquals(StarterPackState.NotStarted, StarterPackState.of(pack, state(0), started = false))
        assertEquals(StarterPackState.Downloading(1, 4), StarterPackState.of(pack, state(1), started = true))
        assertEquals(StarterPackState.Ready, StarterPackState.of(pack, state(4), started = true))
        // Installed before (from the Dictionaries tab, say): ready without a download.
        assertEquals(StarterPackState.Ready, StarterPackState.of(pack, state(4), started = false))
        val failed = ImportStatus.Failed("x", ImportError.entries.first(), null)
        assertEquals(StarterPackState.Failed(), StarterPackState.of(pack, state(2, listOf(failed)), started = true))
    }

    @Test
    fun aFailureWaitsForTheDownloadsStillRunning() {
        val failed = ImportStatus.Failed("x", ImportError.entries.first(), null, pack[0].id)
        fun loaded(running: Set<Int>) = DictionariesUiState.Loaded(
            dictionaries = emptyList(),
            imports = listOf(failed),
            catalog = DictionaryCatalog.dictionaries.map { dictionary ->
                val index = pack.indexOf(dictionary)
                CatalogItem(dictionary, if (index in running) CatalogItem.State.IN_PROGRESS else CatalogItem.State.AVAILABLE)
            },
        )
        // One failed, two still running: still downloading.
        assertEquals(StarterPackState.Downloading(0, 4), StarterPackState.of(pack, loaded(setOf(1, 2)), started = true))
        // Nothing left running: failed.
        assertEquals(StarterPackState.Failed(), StarterPackState.of(pack, loaded(emptySet()), started = true))
    }

    @Test
    fun aFailureCarriesItsReason() {
        val offline = ImportStatus.Failed("x", ImportError.DOWNLOAD_FAILED, null, pack[0].id, FailureReason.OFFLINE)
        assertEquals(
            StarterPackState.Failed(FailureReason.OFFLINE),
            StarterPackState.of(pack, state(0, listOf(offline)), started = true),
        )
    }

    @Test
    fun everyReasonHasItsOwnSentence() {
        assertEquals(FailureReason.entries.size, FailureReason.entries.map { it.messageRes() }.toSet().size)
    }

    @Test
    fun progressFollowsTheRunningStep() {
        fun running(download: DownloadProgress? = null, progress: ImportProgress? = null) =
            listOf(ImportStatus.Running("r", progress, download))
        // Nothing reported yet, or an unknown size: no number to show.
        assertNull(StarterPackState.fractionOf(1, 4, emptyList()))
        assertNull(StarterPackState.fractionOf(1, 4, running()))
        assertNull(StarterPackState.fractionOf(1, 4, running(DownloadProgress("a", 10, -1))))
        // Half-way through the download: a quarter of a step in.
        assertEquals(0.3125f, StarterPackState.fractionOf(1, 4, running(DownloadProgress("a", 50, 100)))!!, 1e-6f)
        // Importing, 50 %: three quarters of the step.
        assertEquals(0.4375f, StarterPackState.fractionOf(1, 4, running(progress = ImportProgress("a", 50)))!!, 1e-6f)
        // The state carries it.
        val withRunning = state(1, running(DownloadProgress("a", 100, 100))).copy(
            catalog = state(1).catalog.map {
                if (it.dictionary == pack[1]) CatalogItem(it.dictionary, CatalogItem.State.IN_PROGRESS) else it
            },
        )
        assertEquals(
            StarterPackState.Downloading(1, 4, 0.375f),
            StarterPackState.of(pack, withRunning, started = true),
        )
    }

    @Test
    fun aFailedStarterDownloadIsFoundForTheLibraryNotice() {
        val japanese = DictionaryCatalog.starterPack(japanese = true).first()
        fun failed(id: String?, reason: FailureReason = FailureReason.SERVER) =
            ImportStatus.Failed("f", ImportError.DOWNLOAD_FAILED, null, id, reason)
        assertNull(StarterPackState.failedDownload(DictionariesUiState.Loading))
        // Nothing failed, or running fine: no notice.
        assertNull(StarterPackState.failedDownload(state(1)))
        assertNull(StarterPackState.failedDownload(state(1, listOf(ImportStatus.Running("r", null)))))
        // A failed import of a file, or of a dictionary outside the starter packs: no notice.
        assertNull(StarterPackState.failedDownload(state(0, listOf(failed(null)))))
        assertNull(StarterPackState.failedDownload(state(0, listOf(failed("not-in-a-pack")))))
        // A starter dictionary of either language still missing: notice with its reason.
        assertEquals(FailureReason.SERVER, StarterPackState.failedDownload(state(0, listOf(failed(pack[0].id))))?.reason)
        assertEquals(japanese.id, StarterPackState.failedDownload(state(0, listOf(failed(japanese.id))))?.dictionaryId)
        // Installed since (from Settings, say): no notice.
        assertNull(StarterPackState.failedDownload(state(1, listOf(failed(pack[0].id)))))
        // Finished: all installed.
        assertNull(StarterPackState.failedDownload(state(4)))
    }
}
