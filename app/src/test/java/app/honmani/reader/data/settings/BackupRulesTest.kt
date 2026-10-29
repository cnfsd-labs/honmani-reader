package app.honmani.reader.data.settings

import app.honmani.reader.AppContainer
import app.honmani.reader.data.backup.AutoBackup
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Cloud backup and device transfer keep only the reader's choices and Honmani's own backup of
 * itself: anything more (books, fonts, ttu's copies) would be orphans without the database and
 * push the backup past Android's limit.
 */
class BackupRulesTest {
    private val onlySettings = listOf(
        "include sharedpref ${AppContainer.SETTINGS}.xml",
        "include file ${AutoBackup.DIRECTORY}/",
    )

    @Test
    fun backupKeepsOnlyTheSettingsAndTheAutoBackup() {
        val rules = rules("data_extraction_rules.xml")

        assertEquals(onlySettings, rules.section("cloud-backup"))
        assertEquals(onlySettings, rules.section("device-transfer"))
    }

    @Test
    fun androidElevenAndLowerKeepTheSame() {
        assertEquals(onlySettings, rules("backup_rules.xml").section("full-backup-content"))
    }

    private fun rules(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/$name")).documentElement

    private fun Element.section(tag: String): List<String> {
        val section = if (tagName == tag) this else getElementsByTagName(tag).item(0) as Element
        val nodes = section.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .map { "${it.tagName} ${it.getAttribute("domain")} ${it.getAttribute("path")}" }
    }
}
