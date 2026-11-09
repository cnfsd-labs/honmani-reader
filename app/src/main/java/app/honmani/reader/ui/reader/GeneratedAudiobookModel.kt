package app.honmani.reader.ui.reader

import app.honmani.reader.anki.CardVoice
import app.honmani.reader.anki.CloudVoiceInfo
import app.honmani.reader.anki.GoogleCloudVoice
import app.honmani.reader.anki.VoiceException
import app.honmani.reader.anki.VoiceProblem
import app.honmani.reader.readaloud.generate.AudiobookJobState
import app.honmani.reader.readaloud.generate.AudiobookMaker
import app.honmani.reader.readaloud.generate.AudiobookScheduler
import app.honmani.reader.readaloud.generate.FrozenPlan
import app.honmani.reader.readaloud.generate.GeneratedAudiobook
import app.honmani.reader.readaloud.generate.MakerStatus
import app.honmani.reader.readaloud.generate.SpeechPlan
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the reader can't start making an audiobook from the intro, or null if it can. */
enum class MakeBlock {
    /** Only EPUB books can follow an audiobook. */
    EPUB_ONLY,

    /** There is no Google key to bill. */
    NO_KEY,
}

/** A Japanese voice of Google's list with its price per million characters, null if Honmani doesn't know it. */
data class VoiceChoice(val name: String, val label: String, val pricePerMillion: Double?)

/**
 * What the confirmation sheet shows and what Start would do. The numbers are worked out here, so the
 * sheet only draws them: [canStart] is false while anything is missing or over the cap.
 */
data class MakeState(
    /** Counting the book's characters and asking Google for the voices (this also checks the key). */
    val loading: Boolean = false,
    /** Google refused the key or could not be reached; nothing was billed. */
    val problem: VoiceProblem? = null,
    /** The book could not be read, or has no text. */
    val unreadable: Boolean = false,
    /** Characters that will be sent (the spoken text). */
    val chars: Long = 0,
    val voices: List<VoiceChoice> = emptyList(),
    val voice: String? = null,
    /** A price the reader typed for a voice Honmani has no price for. */
    val typedPrice: String = "",
    val capText: String = "",
    val mobileData: Boolean = false,
    val freeBytes: Long = Long.MAX_VALUE,
    /** Start was pressed and the job is queued. */
    val started: Boolean = false,
) {
    val voiceChoice: VoiceChoice? get() = voices.firstOrNull { it.name == voice }

    /** USD per million characters: Honmani's own price for the voice, else the one typed. */
    val price: Double? get() {
        val name = voice ?: return null
        return GeneratedAudiobook.pricePerMillion(name) ?: parseDecimal(typedPrice)?.takeIf { it > 0 }
    }

    /** The voice needs a price from the reader before anything can be estimated. */
    val needsPrice: Boolean get() = voice != null && GeneratedAudiobook.pricePerMillion(voice) == null

    val estimateUsd: Double? get() = price?.let { estimate(chars, it) }
    val cap: Double? get() = parseDecimal(capText)
    val overCap: Boolean get() = (estimateUsd ?: return false) > (cap ?: return false)
    val spaceNeededBytes: Long get() = GeneratedAudiobook.spaceNeededBytes(chars)
    val spaceShort: Boolean get() = freeBytes < spaceNeededBytes
    val seconds: Long get() = GeneratedAudiobook.estimatedSeconds(chars)
    val bytes: Long get() = GeneratedAudiobook.estimatedBytes(chars)

    val ready: Boolean get() = !loading && problem == null && !unreadable && voice != null && chars > 0

    val canStart: Boolean get() = ready && !started && price != null && (cap ?: 0.0) > 0 && !overCap && !spaceShort

    companion object {
        /** A number typed with a point or a comma; null if it is not one. */
        fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

        /** USD for [chars] characters at [pricePerMillion] USD per million. */
        fun estimate(chars: Long, pricePerMillion: Double): Double =
            BigDecimal(chars).multiply(BigDecimal.valueOf(pricePerMillion)).divide(BigDecimal(1_000_000)).toDouble()

        /** The estimate plus 10%, rounded up to a cent, as text for the cap field. */
        fun defaultCapText(estimateUsd: Double): String =
            BigDecimal.valueOf(estimateUsd).multiply(BigDecimal("1.1")).setScale(2, RoundingMode.CEILING).toPlainString()
    }
}

/**
 * The confirmation before a generated audiobook is made: counts the book's spoken characters off
 * the main thread, asks Google for the voices (which also checks the key before anything is
 * billed), keeps the reader's choices and, on Start, freezes the plan and queues the job once.
 * Nothing is sent to Google until [start]; the voice list is not billed.
 */
class GeneratedAudiobookModel(
    private val bookHash: String,
    private val epub: Boolean,
    private val key: () -> String?,
    private val loadVoices: suspend (key: String) -> List<CloudVoiceInfo>,
    private val readPlan: () -> SpeechPlan?,
    private val maker: AudiobookMaker,
    private val scheduler: AudiobookScheduler,
    private val freeBytes: () -> Long,
    private val savedVoice: () -> String?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val makeVoice: (key: String, voice: String) -> CardVoice = { apiKey, voice -> GoogleCloudVoice(apiKey, voice) },
) {
    private val _state = MutableStateFlow(MakeState())
    val state: StateFlow<MakeState> = _state.asStateFlow()

    private var plan: SpeechPlan? = null
    private var capEdited = false
    private var loadJob: Job? = null
    private var starting = false

    /** What stops the intro's button, or null. A missing key is checked first: the epub question is the book's, the key the reader's. */
    fun block(): MakeBlock? = when {
        !epub -> MakeBlock.EPUB_ONLY
        key().isNullOrBlank() -> MakeBlock.NO_KEY
        else -> null
    }

    /** Opens the sheet: counts and lists the voices; does nothing again while it is loading or done. */
    fun open() {
        if (block() != null || loadJob?.isActive == true || plan != null && _state.value.problem == null) return
        val apiKey = key() ?: return
        _state.update { MakeState(loading = true, mobileData = it.mobileData) }
        capEdited = false
        loadJob = scope.launch {
            run {
                val (counted, voices) = coroutineScope {
                    val counting = async(io) { readPlan() }
                    val listed = try {
                        Result.success(loadVoices(apiKey))
                    } catch (e: VoiceException) {
                        Result.failure(e)
                    }
                    counting.await() to listed
                }
                val problem = (voices.exceptionOrNull() as? VoiceException)?.problem
                if (problem != null) {
                    _state.update { it.copy(loading = false, problem = problem) }
                    return@launch
                }
                if (counted == null || counted.billedChars == 0L) {
                    _state.update { it.copy(loading = false, unreadable = true) }
                    return@launch
                }
                plan = counted
                val choices = voices.getOrThrow().map { VoiceChoice(it.name, "${it.shortName} (${it.model})", GeneratedAudiobook.pricePerMillion(it.name)) }
                val chosen = savedVoice()?.takeIf { saved -> choices.any { it.name == saved } }
                    ?: GoogleCloudVoice.defaultVoice(voices.getOrThrow())?.name
                _state.update {
                    it.copy(loading = false, chars = counted.billedChars, voices = choices, voice = chosen, freeBytes = freeBytes())
                }
                refreshCap()
            }
        }
    }

    /** The voice chosen in the sheet (not the one in Settings) for a sample, or null without a key or a choice. */
    fun sampleVoice(): CardVoice? {
        val apiKey = key()?.takeIf { it.isNotBlank() } ?: return null
        val name = _state.value.voice ?: return null
        return makeVoice(apiKey, name)
    }

    fun chooseVoice(name: String) {
        _state.update { it.copy(voice = name) }
        refreshCap()
    }

    fun typePrice(text: String) {
        _state.update { it.copy(typedPrice = text) }
        refreshCap()
    }

    fun typeCap(text: String) {
        capEdited = true
        _state.update { it.copy(capText = text) }
    }

    fun setMobileData(on: Boolean) = _state.update { it.copy(mobileData = on) }

    /** The cap follows the estimate until the reader has typed one. */
    private fun refreshCap() {
        if (capEdited) return
        _state.update { s -> s.copy(capText = s.estimateUsd?.let(MakeState::defaultCapText).orEmpty()) }
    }

    /**
     * Freezes the plan, then queues the job; a second call does nothing (the scheduler's start
     * replaces a running job, so a double tap must not reach it). Returns whether it started.
     */
    fun start(): Boolean {
        val s = _state.value
        val counted = plan
        val voice = s.voice
        val price = s.price
        val cap = s.cap
        if (starting || !s.canStart || counted == null || voice == null || price == null || cap == null) return false
        starting = true
        _state.update { it.copy(started = true) }
        scope.launch {
            withContext(io) {
                // A plan from an earlier start stays (freeze says so); the scheduler takes the cap.
                maker.freeze(FrozenPlan(bookHash, voice, price, cap, s.mobileData, SAMPLE_RATE, counted.sentences))
            }
            scheduler.start(bookHash, voice, cap, s.mobileData)
        }
        return true
    }

    companion object {
        /** The rate asked of the voice (`FrozenPlan.sampleRate`); the maker stops on any other. */
        const val SAMPLE_RATE = 24_000
    }
}

/** What the manage step shows of a generated audiobook: what is on disk and where the job is. */
data class GeneratedManage(
    val status: MakerStatus,
    val job: AudiobookJobState,
    /** Bytes of audio on the phone (the finished file, or what is made so far). */
    val sizeBytes: Long,
) {
    /** Only a partial audiobook is being made; a finished one is just played or deleted. */
    val partial: MakerStatus.Partial? get() = status as? MakerStatus.Partial

    companion object {
        /** The cap to offer after a stop at [status]'s cap: what the whole book is projected to cost, plus 10%, never below 1.5 times the old cap. */
        fun raisedCapText(status: MakerStatus.Partial): String {
            val projected = if (status.done > 0) status.spentUsd * status.total / status.done * 1.1 else 0.0
            val value = maxOf(projected, status.capUsd * 1.5)
            return BigDecimal.valueOf(value).setScale(2, RoundingMode.CEILING).toPlainString()
        }
    }
}

/** The manage state of [bookHash], or null when the book has nothing generated (a recording the reader paired, or nothing). Follows the job. */
fun generatedManage(maker: AudiobookMaker, scheduler: AudiobookScheduler, bookHash: String): Flow<GeneratedManage?> =
    scheduler.state(bookHash).map { job ->
        val status = maker.status(bookHash)
        if (status is MakerStatus.None) {
            null
        } else {
            val files = maker.files(bookHash)
            GeneratedManage(status, job, (if (files.audio.isFile) files.audio else files.part).takeIf { it.isFile }?.length() ?: 0L)
        }
    }.flowOn(Dispatchers.IO)
