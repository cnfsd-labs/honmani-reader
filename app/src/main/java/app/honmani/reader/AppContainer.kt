package app.honmani.reader

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import app.honmani.reader.anki.AnkiDroid
import app.honmani.reader.anki.AnkiExporter
import app.honmani.reader.anki.AnkiSettings
import app.honmani.reader.anki.PhoneVoice
import app.honmani.reader.anki.WordDetails
import app.honmani.reader.data.HonmaniDatabase
import app.honmani.reader.data.backup.Backup
import app.honmani.reader.data.backup.RestoredBooks
import app.honmani.reader.data.dictionary.DictionaryDownloader
import app.honmani.reader.data.dictionary.DictionaryImportScheduler
import app.honmani.reader.data.dictionary.DictionaryImporter
import app.honmani.reader.data.dictionary.DictionaryRepository
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
import app.honmani.reader.data.fonts.FontStore
import app.honmani.reader.data.library.BookDao
import app.honmani.reader.data.library.BookFormat
import app.honmani.reader.data.library.BookImporter
import app.honmani.reader.data.net.LoginGate
import app.honmani.reader.data.net.NetworkWatch
import app.honmani.reader.data.opds.KeystoreCredentialCipher
import app.honmani.reader.data.opds.OpdsAutoImport
import app.honmani.reader.data.opds.OpdsClient
import app.honmani.reader.data.opds.OpdsRepository
import app.honmani.reader.data.settings.AppearanceSettings
import app.honmani.reader.data.settings.OnboardingSettings
import app.honmani.reader.data.stats.MilestoneStore
import app.honmani.reader.data.stats.ReadingDao
import app.honmani.reader.data.stats.ReadingDayEntity
import app.honmani.reader.data.stats.ReadingStore
import app.honmani.reader.data.stats.ReadingTracker
import app.honmani.reader.data.stats.SkillTracker
import app.honmani.reader.data.sync.EpubPositions
import app.honmani.reader.data.sync.KoSyncClient
import app.honmani.reader.data.sync.KoSyncSettings
import app.honmani.reader.data.sync.ProgressSync
import app.honmani.reader.data.sync.RubyText
import app.honmani.reader.data.sync.UnsentPositions
import app.honmani.reader.data.words.BookCoverage
import app.honmani.reader.data.words.ReadStretchStore
import app.honmani.reader.data.words.SpellingVariants
import app.honmani.reader.data.words.WordKnowledge
import app.honmani.reader.data.words.WordKnowledgeTracker
import app.honmani.reader.data.words.WordMarker
import app.honmani.reader.data.words.WordProgressStore
import app.honmani.reader.data.words.WordRepository
import app.honmani.reader.diagnostics.ReaderDiagnostics
import app.honmani.reader.lookup.DictionaryLookup
import app.honmani.reader.lookup.LookupGroups
import app.honmani.reader.ui.library.OpenedBooks
import app.honmani.reader.ui.reader.TtuLibrary
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
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    val dictionaryLookup: DictionaryLookup by lazy {
        DictionaryLookup(database.dictionaryDao(), database.termDao(), database.termMetaDao(), database.kanjiDao())
    }

    val readerDiagnostics = ReaderDiagnostics()

    /** Book files from other apps, on their way to the library. */
    val openedBooks = OpenedBooks()

    /** The reader's choices; the only file cloud backup keeps (`res/xml/data_extraction_rules.xml`). */
    private val settingsPreferences by lazy { appContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE) }

    /**
     * What only makes sense on this phone, never backed up: with the database and the books left
     * out of backup, a restored "first start done" would skip the dictionary download.
     */
    private val devicePreferences by lazy { appContext.getSharedPreferences(DEVICE_SETTINGS, Context.MODE_PRIVATE) }

    val appearanceSettings: AppearanceSettings by lazy { AppearanceSettings(settingsPreferences) }

    val onboardingSettings: OnboardingSettings by lazy { OnboardingSettings(devicePreferences) }

    val booksDir = File(appContext.filesDir, "books")

    val fontsDir = File(appContext.filesDir, "fonts")

    val fontStore: FontStore by lazy { FontStore(fontsDir, applicationScope, dictionaryDownloader::download) }

    val bookDao: BookDao by lazy { database.bookDao() }

    val bookImporter: BookImporter by lazy {
        BookImporter(bookDao, booksDir, File(appContext.filesDir, "covers"), restore = { backup.onBookAdded(it) })
    }

    /** One for the app: catalogue passwords and the Google key share its Keystore key. */
    private val credentialCipher = KeystoreCredentialCipher()

    val wordRepository: WordRepository by lazy { WordRepository(database) }

    val ankiSettings: AnkiSettings by lazy {
        AnkiSettings(settingsPreferences, credentialCipher, devicePreferences)
    }

    val ankiDroid: AnkiDroid by lazy { AnkiDroid(appContext) }

    val koSyncSettings: KoSyncSettings by lazy { KoSyncSettings(devicePreferences, credentialCipher) }

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
            }
        }
        ReadingTracker(store, applicationScope, log = { Log.i("Honmani.stats", it) }).also { tracker ->
            applicationScope.launch {
                ttuLibrary.progress.collect { books ->
                    tracker.onPositions(books.mapNotNull { book -> book.characters?.let { book.id to it } }.toMap())
                }
            }
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
            texts = { book ->
                val file = bookImporter.bookFile(book)
                when (book.format) {
                    BookFormat.EPUB -> EpubPositions.texts(file)?.asSequence()
                    // Stored as UTF-8 (see TextEncoding).
                    BookFormat.TXT -> if (file.exists()) sequenceOf(RubyText(file.readText())) else null
                }
            },
            log = { Log.i("Honmani.difficulty", it) },
        )
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
            words = wordRepository.all,
            bookWords = difficultyStore::words,
            store = wordProgressStore,
            variants = { spellingVariants() },
        ).knowledge.shareIn(applicationScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    /**
     * How much of a graded book's words the reader knows, once their vocabulary is estimated
     * (the skill's vocabulary part); null before.
     */
    fun bookCoverage(contentHash: String): Flow<BookCoverage?> {
        var words: Pair<StoredDifficulty, BookWords?>? = null
        return combine(
            wordKnowledge,
            skillTracker.estimate.map { it.vocabulary?.estimate?.words }.distinctUntilChanged(),
            difficultyStore.grades.map { it[contentHash] }.distinctUntilChanged(),
        ) { knowledge, vocabulary, grade ->
            if (vocabulary == null || grade == null || grade.difficulty.score <= 0) return@combine null
            // The book's words only change when it is graded again.
            val bookWords = words?.takeIf { it.first == grade }?.second
                ?: difficultyStore.words(contentHash).also { words = grade to it }
            bookWords?.let { BookCoverage.of(it, knowledge, vocabulary) }
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
        )
    }

    /**
     * Grades books in the background whenever the library or the dictionaries change, once per
     * app start; the stored grades are read first, for the book sheets.
     */
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
