package app.honmani.reader.ui.settings

import app.honmani.reader.data.fonts.FontCatalog
import app.honmani.reader.data.fonts.FontStore
import app.honmani.reader.data.settings.ReaderTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import app.honmani.reader.ui.reader.LibraryMessage
import app.honmani.reader.ui.reader.TtuLibrary
import app.honmani.reader.ui.reader.TtuPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSettingsViewModelTest {
    private class FakePage : TtuPage {
        val scripts = mutableListOf<String>()

        override fun evaluate(script: String) {
            scripts += script
        }

        override fun load(path: String) = Unit
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val ttu = TtuLibrary(pageTimeoutMs = 1_000)
    private val page = FakePage()
    private val dispatcher = UnconfinedTestDispatcher()
    private val font = FontCatalog.fonts.first { it.name == "Klee One" }
    private val fonts by lazy {
        FontStore(
            dir = folder.root,
            scope = CoroutineScope(dispatcher),
            // Not checked against the catalog checksum: the tests put files in place directly.
            download = { _, _, _ -> throw IOException("offline") },
            io = dispatcher,
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ttu.attach(page)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun answerRead(values: Map<String, String?>) {
        val requestId = Json.parseToJsonElement(page.scripts.last().substringAfter("(").substringBeforeLast(")"))
            .jsonObject["requestId"]!!.jsonPrimitive.long
        ttu.onMessage(LibraryMessage.Settings(requestId, values))
    }

    @Test
    fun onlyChangesAreWritten_andTheReaderReloadsOnceWhenClosed() = runTest {
        val viewModel = ReaderSettingsViewModel(ttu, fonts)
        viewModel.load()
        answerRead(mapOf("fontSize" to "28", "theme" to "auto"))
        val loaded = (viewModel.state.value as ReaderSettingsState.Loaded).settings
        assertEquals(28, loaded.fontSize)

        val before = page.scripts.size
        viewModel.update(loaded.copy(fontSize = 30))
        viewModel.update(loaded.copy(fontSize = 30, theme = ReaderTheme.SEPIA))
        val writes = page.scripts.drop(before)
        assertEquals(2, writes.size)
        assertTrue(writes[0], writes[0].contains("\"fontSize\":\"30\"") && !writes[0].contains("theme"))
        assertTrue(writes[1], writes[1].contains("\"theme\":\"ecru-theme\"") && !writes[1].contains("fontSize"))

        viewModel.close()
        viewModel.close()
        assertEquals(1, page.scripts.count { it.contains("reload()") })
    }

    @Test
    fun deletingTheFontInUse_goesBackToSerif_andReloads() = runTest {
        File(folder.root, font.fileName).writeText("font")
        val viewModel = ReaderSettingsViewModel(ttu, fonts)
        viewModel.load()
        answerRead(mapOf("fontFamilyGroupOne" to "Honmani Klee One"))
        // ttu's list is refreshed when the screen opens.
        assertTrue(page.scripts.last(), page.scripts.last().contains("honmaniReaderSettings.fonts(") && page.scripts.last().contains("KleeOne-Regular.ttf"))

        viewModel.deleteFont(font)

        assertEquals("serif", (viewModel.state.value as ReaderSettingsState.Loaded).settings.font)
        assertTrue(page.scripts.any { it.contains("\"fontFamilyGroupOne\":\"serif\"") })
        assertTrue(page.scripts.last(), page.scripts.last().contains("\"fonts\":[]"))
        viewModel.close()
        assertEquals(1, page.scripts.count { it.contains("reload()") })
    }

    @Test
    fun nothingChanged_noReload() = runTest {
        val viewModel = ReaderSettingsViewModel(ttu, fonts)
        viewModel.load()
        answerRead(emptyMap())
        viewModel.close()
        assertTrue(page.scripts.none { it.contains("reload()") })
    }

    @Test
    fun edgeTapsHint_onlyWhenTurnedOn_andOnlyWhileTheStoreAllowsIt() = runTest {
        var allowed = 1
        val viewModel = ReaderSettingsViewModel(ttu, fonts, takeEdgeTapsHint = { allowed-- > 0 })
        viewModel.load()
        answerRead(emptyMap())
        val loaded = (viewModel.state.value as ReaderSettingsState.Loaded).settings
        viewModel.update(loaded.copy(fontSize = loaded.fontSize + 1))
        assertFalse(viewModel.edgeTapsHint.value)

        viewModel.update(loaded.copy(edgeTaps = true))
        assertTrue(viewModel.edgeTapsHint.value)
        viewModel.edgeTapsHintShown()
        assertFalse(viewModel.edgeTapsHint.value)

        viewModel.update(loaded.copy(edgeTaps = false))
        viewModel.update(loaded.copy(edgeTaps = true))
        assertFalse(viewModel.edgeTapsHint.value)
    }
}
