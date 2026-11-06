package app.honmani.reader.ui.reader

/** What the phone's volume buttons do over a book. */
object VolumeKeys {
    /**
     * Whether they turn pages: the reader switched that on, and nothing is read to the reader. While
     * read aloud or the audiobook is on (its bar is shown) they change the volume, as everywhere else;
     * so do they after Listen or Read sentence in the lookup panel, until the panel closes.
     */
    fun turnPages(setting: Boolean, listening: Boolean, panelSpeaking: Boolean = false): Boolean =
        setting && !listening && !panelSpeaking
}
