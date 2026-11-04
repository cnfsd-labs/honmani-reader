package app.honmani.reader

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import app.honmani.reader.anki.AnkiDroid
import app.honmani.reader.anki.AnkiExporter
import app.honmani.reader.anki.AnkiSettings
import app.honmani.reader.anki.AnkiSync
import app.honmani.reader.anki.PhoneVoice
import app.honmani.reader.anki.WordAudio
import app.honmani.reader.anki.WordDetails
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.backup.AutoBackup
import app.honmani.reader.data.backup.ContentFolderTarget
import app.honmani.reader.data.backup.FolderBackup
import app.honmani.reader.data.backup.Backup
import app.honmani.reader.data.backup.RestoredBooks
import app.honmani.reader.data.dictionary.DictionaryDownloader
import app.honmani.reader.data.dictionary.DictionaryImportScheduler
import app.honmani.reader.data.dictionary.DictionaryImporter
import app.honmani.reader.data.dictionary.DictionaryRepository
import app.honmani.reader.data.dictionary.GlossIndex
import app.honmani.reader.data.dictionary.DictionaryUpdateChecker
import app.honmani.reader.data.dictionary.WorkManagerDictionaryImportScheduler
import app.honmani.reader.data.difficulty.BookGrader
import app.honmani.reader.data.difficulty.BookWords
import app.honmani.reader.data.difficulty.DifficultyStore
import app.honmani.reader.data.difficulty.DifficultyWorker
import app.honmani.reader.data.difficulty.Lexicon
import app.honmani.reader.data.difficulty.LexiconLoader
import app.honmani.reader.data.difficulty.LexiconSources
import app.honmani.reader.data.difficulty.StoredDifficulty
import app.honmani.reader.data.examples.ExampleSentences
import app.honmani.reader.data.examples.ExampleStore
import app.honmani.reader.data.fonts.FontStore
import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookmarkDao
import app.honmani.reader.data.library.HighlightDao
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookEntity
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.library.BookText
import app.honmani.reader.data.net.LoginGate
import app.honmani.reader.data.net.NetworkWatch
import app.honmani.reader.data.opds.KeystoreCredentialCipher
import app.honmani.reader.data.opds.OpdsAutoImport
import app.honmani.reader.data.opds.OpdsClient
import app.honmani.reader.data.opds.OpdsRepository
import app.honmani.reader.data.review.ReadingPasses
import app.honmani.reader.data.words.LookupOrigin
import app.honmani.reader.data.words.LookedUpWord
import app.honmani.reader.data.difficulty.Segmenter
import app.honmani.reader.data.review.BookSentences
import app.honmani.reader.data.review.Relevance
import app.honmani.reader.data.review.ReviewFilter
import app.honmani.reader.data.review.ReviewScope
import app.honmani.reader.data.review.ReviewRepository
import app.honmani.reader.data.settings.AppearanceSettings
import app.honmani.reader.data.settings.OnboardingSettings
import app.honmani.reader.data.settings.SearchHistory
import app.honmani.reader.data.settings.ReadingGoalSettings
import app.honmani.reader.data.settings.ReadingTimeSettings
import app.honmani.reader.data.settings.RefoldExportSettings
import app.honmani.reader.data.settings.DaySettings
import app.honmani.reader.data.settings.ReviewSettings
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.stats.ReadingDao
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.stats.ReadingStore
import app.honmani.reader.data.stats.FinishedBookDao
import app.honmani.reader.data.stats.ReadingTracker
import app.honmani.reader.data.stats.SkillTracker
import app.honmani.reader.data.time.DayClock
import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.data.sync.KoSyncClient
import app.honmani.reader.data.sync.KoSyncSettings
import app.honmani.reader.data.sync.KoSyncStatus
import app.honmani.reader.data.sync.ProgressSync
import app.honmani.reader.data.sync.RubyText
import app.honmani.reader.data.sync.SectionStarts
import app.honmani.reader.data.sync.UnsentPositions
import app.honmani.reader.data.words.BookCoverage
import app.honmani.reader.data.words.UnknownWords
import app.honmani.reader.data.words.ReadStretchStore
import app.honmani.reader.data.words.ReadStretches
import app.honmani.reader.data.words.SpellingVariants
import app.honmani.reader.data.words.WordKnowledge
import app.honmani.reader.data.words.WordKnowledgeTracker
import app.honmani.reader.data.words.WordMarker
import app.honmani.reader.data.words.LookupRecorder
import app.honmani.reader.data.words.WordProgressStore
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.data.words.WordStatus
import app.honmani.reader.diagnostics.ReaderDiagnostics
import app.honmani.reader.lookup.DictionaryLookup
import app.honmani.reader.lookup.LookupGroups
import app.honmani.reader.ui.library.OpenedBooks
import app.honmani.reader.ui.search.SearchRequests
import app.honmani.reader.ui.reader.TtuLibrary
import app.honmani.reader.ui.reader.TtuLibraryException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Manual dependency container. Objects are created lazily and live as long as the process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database: HonmaniDatabase by lazy { HonmaniDatabase.create(appContext) }

    /** For work that outlives a screen, such as automatic imports. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dictionaryRepository: DictionaryRepository by lazy {
        DictionaryRepository(database.dictionaryDao())
    }

    val dictionaryImporter: DictionaryImporter by lazy { DictionaryImporter(database) }

    val dictionaryDownloader = DictionaryDownloader()

    val dictionaryUpdateChecker: DictionaryUpdateChecker by lazy {
        DictionaryUpdateChecker(
            dao = database.dictionaryDao(),
            fetchText = dictionaryDownloader::fetchText,
            scope = applicationScope,
            log = { Log.i("Honmani.dictionaries", it) },
        )
    }

    /** English search: the glosses of each dictionary, indexed in the background ([startGlossIndex]). */
    val glossIndex: GlossIndex by lazy { GlossIndex(database) }

    val dictionaryLookup: DictionaryLookup by lazy {
        DictionaryLookup(database.dictionaryDao(), database.termDao(), database.termMetaDao(), database.kanjiDao(), glossIndex)
    }

    val readerDiagnostics = ReaderDiagnostics()

    /** Book files from other apps, on their way to the library. */
    val openedBooks = OpenedBooks()
    val searchRequests = SearchRequests()

    /** The reader's choices; the only file cloud backup keeps (`res/xml/data_extraction_rules.xml`). */
    private val settingsPreferences by lazy { appContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE) }

    /**
     * What only makes sense on this phone, never backed up: with the database and the books left
     * out of backup, a restored "first start done" would skip the dictionary download.
     */
    private val devicePreferences by lazy { appContext.getSharedPreferences(DEVICE_SETTINGS, Context.MODE_PRIVATE) }

    val appearanceSettings: AppearanceSettings by lazy { AppearanceSettings(settingsPreferences) }

    /** The daily reading goal (Settings; shown in the statistics). */
    val readingGoalSettings: ReadingGoalSettings by lazy { ReadingGoalSettings(settingsPreferences) }

    /** Whether reading time counts while the lookup panel is open (Settings → Reading). */
    val readingTimeSettings: ReadingTimeSettings by lazy { ReadingTimeSettings(settingsPreferences) }

    /** Where the Refold export left off (Progress). */
    val refoldExportSettings: RefoldExportSettings by lazy { RefoldExportSettings(settingsPreferences) }

    /** New cards a day, the card front and the days to "known" (Settings → Flashcards). */
    val reviewSettings: ReviewSettings by lazy { ReviewSettings(settingsPreferences) }

    /** Settings → "Day starts at": when a reading and review day begins. */
    val daySettings: DaySettings by lazy { DaySettings(settingsPreferences) }

    /** Which day a moment belongs to, by [daySettings]; every reading and review day comes from here. */
    val dayClock: DayClock by lazy { DayClock(startHour = { daySettings.startHour.value }) }

    val onboardingSettings: OnboardingSettings by lazy { OnboardingSettings(devicePreferences) }
    val searchHistory: SearchHistory by lazy { SearchHistory(devicePreferences) }

    val booksDir = File(appContext.filesDir, "books")

    val fontsDir = File(appContext.filesDir, "fonts")

    val fontStore: FontStore by lazy { FontStore(fontsDir, applicationScope, dictionaryDownloader::download) }

    /** The optional example sentences (Settings → Example sentences), in `files/examples/`. */
    val exampleStore: ExampleStore by lazy {
        ExampleStore(File(appContext.filesDir, "examples"), applicationScope, dictionaryDownloader::download)
    }

    val exampleSentences: ExampleSentences by lazy { ExampleSentences(exampleStore, appearanceSettings.showExamples) }

    val bookDao: BookDao by lazy { database.bookDao() }
    val bookmarkDao: BookmarkDao by lazy { database.bookmarkDao() }
    val highlightDao: HighlightDao by lazy { database.highlightDao() }

    val bookImporter: BookImporter by lazy {
        BookImporter(bookDao, booksDir, File(appContext.filesDir, "covers"), restore = { backup.onBookAdded(it) })
    }

    /** One for the app: catalogue passwords and the Google key share its Keystore key. */
    private val credentialCipher = KeystoreCredentialCipher()

    val wordRepository: WordRepository by lazy { WordRepository(database) }

    val reviewRepository: ReviewRepository by lazy { ReviewRepository(database, startHour = { daySettings.startHour.value }, relevance = ::openBookRelevance) }

    /** The book being read: the last one opened, unless finished or hidden. */
    private suspend fun openBook(): BookEntity? =
        bookDao.observeAll().first().firstOrNull { it.lastOpenedAt != null }?.takeIf { it.finishedAt == null && !it.hidden }

    /** How soon each word comes up in the book being read, from the reading point on; null without one or before it is graded. */
    private suspend fun openBookRelevance(): ((String) -> Double)? = openBook()?.let { bookFilter(it)?.score }

    /**
     * The words of [book] (or of one of their spellings), ordered by how soon they come up from
     * its reading point; null before the book is graded.
     */
    private suspend fun bookFilter(book: BookEntity): ReviewFilter? {
        val words = withContext(Dispatchers.IO) { difficultyStore.words(book.contentHash) } ?: return null
        val relevance = Relevance.of(words, Relevance.step(book.progress))
        val keys = words.keys.toHashSet()
        val variants = spellingVariants()
        return ReviewFilter(score = { relevance.of(it, variants.of(it).asList()) }) { word ->
            word.expression in keys || variants.of(word.expression).any { it in keys }
        }
    }

    /** What the next review session draws from (the Review card's chips); not kept across starts of the app. */
    val reviewScope = MutableStateFlow<ReviewScope>(ReviewScope.Due)

    /** The words [scope] takes; null for [ReviewScope.Due]. A book not graded yet takes none. */
    suspend fun reviewFilter(scope: ReviewScope): ReviewFilter? = when (scope) {
        ReviewScope.Due -> null
        ReviewScope.ThisBook -> openBook()?.let { bookFilter(it) } ?: ReviewFilter { false }
        is ReviewScope.Book -> bookDao.find(scope.id)?.let { bookFilter(it) } ?: ReviewFilter { false }
        ReviewScope.Starred -> ReviewFilter { it.starred }
        ReviewScope.AllLearning -> ReviewFilter { it.status == WordStatus.LEARNING }
    }

    val ankiSettings: AnkiSettings by lazy {
        AnkiSettings(settingsPreferences, credentialCipher, devicePreferences)
    }

    val ankiDroid: AnkiDroid by lazy { AnkiDroid(appContext) }

    val koSyncSettings: KoSyncSettings by lazy { KoSyncSettings(devicePreferences, credentialCipher) }

    val koSyncStatus: KoSyncStatus by lazy { KoSyncStatus(devicePreferences) }

    /**
     * Over plain http, logins wait for the server to ask, per network, and never go on a
     * hotspot with a login page (see [LoginGate]).
     */
    private val networkWatch: NetworkWatch by lazy { NetworkWatch(appContext) }

    private val loginGate: LoginGate by lazy {
        LoginGate(network = networkWatch::current, hotspot = networkWatch::isHotspot)
    }

    val koSyncClient: KoSyncClient by lazy { KoSyncClient(logins = loginGate) }

    val progressSync: ProgressSync by lazy {
        ProgressSync(
            settings = koSyncSettings,
            client = koSyncClient,
            books = bookDao,
            bookFile = bookImporter::bookFile,
            scope = applicationScope,
            device = "Honmani (${Build.MODEL})",
            unsent = UnsentPositions(File(appContext.filesDir, "sync-unsent.json")),
            status = koSyncStatus,
        ).also { sync ->
            // Pages read without a connection go out when there is one again, or now.
            networkWatch.onAvailable(sync::resend)
            sync.resend()
        }
    }

    /** One phone voice for the app, so its engine stays connected between cards. */
    val phoneVoice: PhoneVoice by lazy { PhoneVoice(appContext, applicationScope) }

    /** Audio for cards waits here until AnkiDroid has copied it; see `res/xml/file_paths.xml`. */
    val ankiAudioDir = File(appContext.cacheDir, "anki-audio")

    /**
     * The lookup panel's listen button: the card voice, or the phone's when card audio is off;
     * its read-aloud button for the sentence always uses the phone's voice.
     */
    val wordAudio: WordAudio by lazy {
        WordAudio(
            voice = { ankiSettings.voice { phoneVoice } ?: phoneVoice },
            dir = File(appContext.cacheDir, "listen"),
            sentenceVoice = { phoneVoice },
        )
    }

    val ankiSync: AnkiSync by lazy { AnkiSync(ankiDroid, ankiSettings, wordRepository) }

    val ankiExporter: AnkiExporter by lazy {
        AnkiExporter(
            anki = ankiDroid,
            settings = ankiSettings,
            words = wordRepository,
            phoneVoice = { phoneVoice },
            audioDir = ankiAudioDir,
            // Off the main thread: "Add all" and the text file look up every word.
            details = { expression, reading ->
                withContext(Dispatchers.Default) {
                    val group = LookupGroups.from(dictionaryLookup.lookup(expression))
                        .firstOrNull { it.expression == expression && it.reading == reading }
                    WordDetails(
                        senses = group?.sections?.firstOrNull()?.senses?.map { it.glosses }.orEmpty(),
                        pitches = group?.pitches.orEmpty(),
                        frequency = group?.frequencies?.firstOrNull()?.label,
                    )
                }
            },
        )
    }

    val opdsClient by lazy { OpdsClient(logins = loginGate) }

    val opdsRepository: OpdsRepository by lazy {
        OpdsRepository(database.opdsCatalogDao(), credentialCipher, opdsClient)
    }

    /** Catalogue downloads wait here before they are added to the library. */
    val downloadsDir = File(appContext.cacheDir, "downloads")

    val opdsAutoImport: OpdsAutoImport by lazy {
        OpdsAutoImport(
            repository = opdsRepository,
            client = opdsClient,
            importer = bookImporter,
            bookDao = bookDao,
            downloadsDir = downloadsDir,
            scope = applicationScope,
            log = { Log.i("Honmani.opds", it) },
        )
    }

    /** Lives with the process: the reader WebView attaches to it, the library drives it. */
    val ttuLibrary = TtuLibrary()

    val readingDao: ReadingDao by lazy { database.readingDao() }

    /** Finished books removed from the library, kept for the "books finished" counts. */
    val finishedBookDao: FinishedBookDao by lazy { database.finishedBookDao() }

    val milestoneStore: MilestoneStore by lazy { MilestoneStore(devicePreferences) }

    /** Counts reading time and characters; fed by the reader screen and ttu's position reports. */
    val readingTracker: ReadingTracker by lazy {
        val store = object : ReadingStore {
            override suspend fun add(ttuId: Long, day: String, seconds: Long, characters: Long) {
                val book = bookDao.findByTtuId(ttuId) ?: return
                database.withTransaction {
                    readingDao.insertIfMissing(ReadingDayEntity(book.id, day))
                    readingDao.add(book.id, day, seconds, characters)
                }
            }

            override suspend fun position(ttuId: Long): Long? = bookDao.findByTtuId(ttuId)?.characters

            override suspend fun setPosition(ttuId: Long, characters: Long) =
                bookDao.setCharactersByTtuId(ttuId, characters)

            override suspend fun addRead(ttuId: Long, from: Float, to: Float) {
                val book = bookDao.findByTtuId(ttuId) ?: return
                withContext(Dispatchers.IO) { readStretches.add(book.contentHash, from, to) }
                passReadWords(book.contentHash, from, to)
            }
        }
        ReadingTracker(store, applicationScope, startHour = { daySettings.startHour.value }, log = { Log.i("Honmani.stats", it) }).also { tracker ->
            applicationScope.launch {
                ttuLibrary.progress.collect { books ->
                    tracker.onPositions(books.mapNotNull { book -> book.characters?.let { book.id to it } }.toMap())
                }
            }
        }
    }

    /** Due words that passed while reading, for the summary when a book closes. */
    val readingPasses = ReadingPasses()

    private val readWordsLock = Mutex()
    private var readWords: Pair<String, BookWords?>? = null

    /**
     * Reading a stretch of a book is a pass for the due words in review met there (once a day,
     * [ReviewRepository.onRead]). Needs the book's words, kept once it is graded.
     */
    private suspend fun passReadWords(contentHash: String, from: Float, to: Float) {
        val keys = HashSet<String>()
        readWordsLock.withLock {
            val words = readWords?.takeIf { it.first == contentHash }?.second
                ?: withContext(Dispatchers.IO) { difficultyStore.words(contentHash) }.also { readWords = contentHash to it }
            words?.forEachRead(ReadStretches.add(ReadStretches.NONE, from, to)) { key, _, _ -> keys += key }
        }
        if (keys.isEmpty()) return
        val variants = spellingVariants()
        val passed = reviewRepository.onReadKeys(keys) { variants.of(it).asList() }
        if (passed.isNotEmpty()) {
            readingPasses.add(passed)
            Log.i("Honmani.review", "Read ${passed.size} due words in review")
        }
    }

    /** Book difficulty, kept per book in app storage (a cache; see [BookGrader]). */
    val difficultyStore: DifficultyStore by lazy { DifficultyStore(File(appContext.filesDir, "difficulty")) }

    val bookGrader: BookGrader by lazy {
        BookGrader(
            store = difficultyStore,
            books = { bookDao.observeAll().first() },
            sources = { lexiconSources() },
            lexicon = { lexicon(it) },
            texts = ::bookTexts,
            log = { Log.i("Honmani.difficulty", it) },
        )
    }

    /** A book's text, chapter by chapter, as the grader reads it. */
    private fun bookTexts(book: BookEntity): Sequence<RubyText>? {
        val file = bookImporter.bookFile(book)
        return when (book.format) {
            BookFormat.EPUB -> EpubPositions.texts(file)?.asSequence()
            // Stored as UTF-8 (see TextEncoding).
            BookFormat.TXT -> if (file.exists()) sequenceOf(RubyText(file.readText())) else null
        }
    }

    /**
     * A book's text for the in-book search (see [BookText]), read off the main thread; null if
     * the library has no such book or its file can't be read.
     */
    suspend fun searchableBook(ttuId: Long): BookText? = withContext(Dispatchers.IO) {
        val book = bookDao.findByTtuId(ttuId) ?: return@withContext null
        val file = bookImporter.bookFile(book)
        val documents = when (book.format) {
            BookFormat.EPUB -> EpubPositions.texts(file, withoutParentheses = true)?.map { it.text }
            // Stored as UTF-8 (see TextEncoding).
            BookFormat.TXT -> if (file.exists()) listOf(file.readText()) else null
        }
        documents?.let(::BookText)
    }

    private val sectionTables = object : LinkedHashMap<String, SectionStarts>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SectionStarts>?) = size > 3
    }

    /**
     * Where each document of an EPUB begins in the book's characters, for drawing and saving
     * highlights (see [SectionStarts]). Worked out once per book (it reads the whole file);
     * null for a book that is not an EPUB or whose file can't be read.
     */
    suspend fun sectionStarts(book: BookEntity): SectionStarts? = withContext(Dispatchers.IO) {
        if (book.format != BookFormat.EPUB) return@withContext null
        synchronized(sectionTables) { sectionTables[book.contentHash] }?.let { return@withContext it }
        val file = bookImporter.bookFile(book).takeIf { it.isFile } ?: return@withContext null
        EpubPositions.sectionStarts(file)?.also { synchronized(sectionTables) { sectionTables[book.contentHash] = it } }
    }

    /**
     * "Prepare a book": adds [keys] (words of [book], see [UnknownWords]) to Learning as new
     * review cards, each with the sentence of the book where it comes up next from the reading
     * point ([BookSentences]). Returns how many words are in review now.
     */
    suspend fun prepareBook(book: BookEntity, keys: List<String>): Int {
        val words = withContext(Dispatchers.IO) { difficultyStore.words(book.contentHash) } ?: return 0
        val lexicon = lexiconSources()?.let { lexicon(it) } ?: return 0
        val sentences = withContext(Dispatchers.Default) {
            val chapters = bookTexts(book)?.map { it.text }?.toList() ?: return@withContext emptyMap()
            val segmenter = Segmenter(lexicon)
            BookSentences.find(chapters, words, keys, Relevance.step(book.progress)) { text, onToken -> segmenter.segment(text, onToken) }
        }
        var added = 0
        for (key in keys) {
            val groups = LookupGroups.from(dictionaryLookup.lookup(key))
            val best = groups.firstOrNull { it.expression == key } ?: groups.firstOrNull() ?: continue
            val sentence = sentences[key]
            val id = wordRepository.prepare(
                LookedUpWord(
                    expression = best.expression,
                    reading = best.reading,
                    gloss = best.sections.firstOrNull()?.senses?.firstOrNull()?.glosses?.joinToString("; ").orEmpty(),
                    surface = sentence?.surface ?: key,
                    sentence = sentence?.text.orEmpty(),
                    surfaceStart = sentence?.start ?: 0,
                    bookId = book.id,
                    bookTitle = book.title,
                    origin = LookupOrigin.PREPARE,
                ),
            )
            reviewRepository.introduce(id)
            if (reviewRepository.card(id) != null) added++
        }
        return added
    }

    private suspend fun lexiconSources(): LexiconSources? =
        LexiconSources.of(database.dictionaryDao().enabledReady()) { database.difficultyDao().hasFrequencies(it.id) }

    private val lexiconLock = Mutex()
    private var sharedLexicon: Pair<String, Lexicon>? = null

    /**
     * The words of [sources] for splitting books, one for grading and word marks. Saved in app
     * storage, so only a new dictionary set or an app update builds it again (several seconds).
     */
    private suspend fun lexicon(sources: LexiconSources): Lexicon = lexiconLock.withLock {
        // An update may change what LexiconLoader adds (grammar words): its time is part of the key.
        val installed = appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
        val signature = "${sources.signature}|$installed"
        sharedLexicon?.takeIf { it.first == signature }?.second ?: withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val file = File(appContext.filesDir, "lexicon.bin")
            val read = Lexicon.read(file, signature)
            (read ?: LexiconLoader(database.difficultyDao()).load(sources).also { runCatching { it.write(file, signature) } })
                .also {
                    sharedLexicon = signature to it
                    val how = if (read != null) "read" else "built"
                    Log.i("Honmani.difficulty", "Lexicon of ${it.size} words $how in ${(System.nanoTime() - started) / 1_000_000} ms")
                }
        }
    }

    private val spellingLock = Mutex()
    private var spellings: Pair<String, SpellingVariants>? = null

    /** Other spellings of the enabled word dictionaries' words, read again when they change. */
    private suspend fun spellingVariants(): SpellingVariants = spellingLock.withLock {
        val terms = database.dictionaryDao().enabledReady().filter { it.termCount > 0 }
        val signature = terms.sortedBy { it.id }.joinToString("|") { "${it.id}@${it.revision}" }
        spellings?.takeIf { it.first == signature }?.second
            ?: run {
                val started = System.nanoTime()
                val cache = File(appContext.filesDir, "spelling-variants.txt")
                SpellingVariants.load(database.difficultyDao(), terms.map { it.id }, cache, signature).also {
                    spellings = signature to it
                    Log.i("Honmani.words", "Spelling variants of ${it.size} forms read in ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            }
    }

    /** The words to mark in the reader (a reader setting). */
    val wordMarker: WordMarker by lazy {
        WordMarker(
            sources = { lexiconSources() },
            lexicon = { lexicon(it) },
            knowledge = { wordKnowledge.first() },
            due = { reviewRepository.dueExpressions(reviewSettings.reviewsPerDay.value) },
        )
    }

    private val _dismissedContinue by lazy { MutableStateFlow(devicePreferences.getString(KEY_DISMISSED_CONTINUE, null)) }

    /**
     * The "Continue reading" bubble swiped away, as `LibraryViewModel.continueKey` (book ids are
     * this phone's): it stays away until a book is opened again.
     */
    val dismissedContinue: StateFlow<String?> get() = _dismissedContinue

    fun dismissContinue(key: String) {
        _dismissedContinue.value = key
        devicePreferences.edit().putString(KEY_DISMISSED_CONTINUE, key).apply()
    }

    /** The parts of each book really read, for the words read. */
    private val readStretches: ReadStretchStore by lazy {
        ReadStretchStore(File(appContext.filesDir, "read-stretches.txt"))
    }

    private val wordProgressStore by lazy { WordProgressStore(File(appContext.filesDir, "word-progress.json")) }

    /** Records the reader's lookups and takes them back ("Don't count"). */
    val lookupRecorder: LookupRecorder by lazy { LookupRecorder(wordRepository, reviewRepository, wordProgressStore) }

    /** What a restored backup keeps for books not back in the library yet. */
    val restoredBooks: RestoredBooks by lazy { RestoredBooks(File(appContext.filesDir, "restored-books.json")) }

    /** Backups of everything but the books (Settings → Back up and restore). */
    val backup: Backup by lazy {
        Backup(
            database = database,
            settings = settingsPreferences,
            milestones = milestoneStore,
            stretches = readStretches,
            wordProgress = wordProgressStore,
            restored = restoredBooks,
            grades = { difficultyStore.grades.value },
            installationId = {
                devicePreferences.getString(KEY_INSTALLATION_ID, null)
                    ?: UUID.randomUUID().toString().also { devicePreferences.edit().putString(KEY_INSTALLATION_ID, it).apply() }
            },
        )
    }

    /** The words read and how well each is known; one computation shared by its screens. */
    val wordKnowledge: Flow<WordKnowledge> by lazy {
        WordKnowledgeTracker(
            books = bookDao.observeAll(),
            stretches = readStretches.stretches,
            grades = difficultyStore.grades,
            words = wordRepository.every,
            bookWords = difficultyStore::words,
            store = wordProgressStore,
            variants = { spellingVariants() },
        ).knowledge.shareIn(applicationScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    /** How much of a graded book's words the reader knows now (see [BookCoverage]); null if it isn't graded. */
    fun bookCoverage(contentHash: String): Flow<BookCoverage?> =
        bookWordsFlow(contentHash) { words, knowledge -> BookCoverage.of(words, knowledge) }

    /** The graded book's words the reader neither knows nor is learning, most used first (see [UnknownWords]). */
    fun unknownWords(contentHash: String): Flow<UnknownWords?> =
        bookWordsFlow(contentHash) { words, knowledge -> UnknownWords.of(words, knowledge) }

    private fun <T : Any> bookWordsFlow(
        contentHash: String,
        compute: (BookWords, WordKnowledge) -> T?,
    ): Flow<T?> {
        var words: Pair<StoredDifficulty, BookWords?>? = null
        return combine(
            wordKnowledge,
            difficultyStore.grades.map { it[contentHash] }.distinctUntilChanged(),
        ) { knowledge, grade ->
            if (grade == null || grade.difficulty.score <= 0) return@combine null
            // The book's words only change when it is graded again.
            val bookWords = words?.takeIf { it.first == grade }?.second
                ?: difficultyStore.words(contentHash).also { words = grade to it }
            bookWords?.let { compute(it, knowledge) }
        }.flowOn(Dispatchers.Default)
    }

    /** The reader's skill: fluency and vocabulary in graded books. */
    val skillTracker: SkillTracker by lazy {
        SkillTracker(
            days = readingDao.observeAll(),
            books = bookDao.observeAll(),
            grades = difficultyStore.grades,
            knowledge = wordKnowledge,
            restored = restoredBooks.scores,
            today = dayClock::today,
        )
    }

    /**
     * Grades books in the background whenever the library or the dictionaries change, once per
     * app start; the stored grades are read first, for the book sheets.
     */
    /** Honmani's own backup of itself, taken along by Android's backup; see [AutoBackup]. */
    val autoBackup: AutoBackup by lazy { AutoBackup(File(appContext.filesDir, AutoBackup.DIRECTORY), devicePreferences) }

    /** The copy of [autoBackup] in a folder the reader chose (Settings → Back up and restore). */
    val folderBackup: FolderBackup by lazy { FolderBackup(devicePreferences, ContentFolderTarget(appContext.contentResolver)) }

    /**
     * Brings [autoBackup] up to date when it is due, as the app goes to the background. ttu's
     * reader settings come from the reader page, so this starts on the main thread.
     */
    fun saveAutoBackup(force: Boolean = false) {
        applicationScope.launch(Dispatchers.Main) {
            val wrote = autoBackup.saveIfDue(force) {
                val readerSettings = try {
                    ttuLibrary.readSettings()
                } catch (_: TtuLibraryException) {
                    null
                }
                backup.export(readerSettings)
            }
            // The same file, also into the folder the reader chose (if any).
            if (wrote) folderBackup.copy(autoBackup.file)
        }
    }

    /** Once per app start: a backup another phone left is offered (see [AutoBackup.offer]). */
    fun checkAutoBackupOffer() {
        applicationScope.launch { autoBackup.checkOffer() }
    }

    /** Once per app start: removes import copies a kill left in the books directory. */
    fun sweepLeftovers() {
        applicationScope.launch { bookImporter.sweepTemporaryFiles() }
    }

    /**
     * Once per app start: keeps [glossIndex] in step with the installed dictionaries (builds what
     * an import or an install from before English search lacks, drops what a removal left).
     */
    fun startGlossIndex() {
        applicationScope.launch {
            dictionaryRepository.dictionaries.map { all -> all.map { it.id }.toSet() }.distinctUntilChanged()
                .collect { glossIndex.sync() }
        }
    }

    fun startGrading() {
        applicationScope.launch {
            withContext(Dispatchers.IO) { difficultyStore.load() }
            combine(
                bookDao.observeAll().map { books -> books.map { it.contentHash }.toSet() }.distinctUntilChanged(),
                dictionaryRepository.dictionaries.map { all -> all.map { "${it.id}:${it.revision}:${it.enabled}" } }.distinctUntilChanged(),
            ) { _, _ -> }.collect { DifficultyWorker.schedule(appContext) }
        }
    }

    val dictionaryImportScheduler: DictionaryImportScheduler by lazy {
        WorkManagerDictionaryImportScheduler(appContext)
    }

    internal companion object {
        /** Shared preferences files; the backup rules in `res/xml` name them as `<name>.xml`. */
        const val SETTINGS = "settings"
        const val DEVICE_SETTINGS = "device"
        private const val KEY_DISMISSED_CONTINUE = "continue_dismissed"

        /** Names this installation in backups (see [Backup]). */
        private const val KEY_INSTALLATION_ID = "installation_id"
    }
}
