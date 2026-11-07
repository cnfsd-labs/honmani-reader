package app.honmani.reader.readaloud.generate

import kotlinx.coroutines.flow.Flow

/** Why a run of the audiobook worker ended without a finished book, kept in `outcome.txt` of the book's folder. */
data class MakerOutcome(val kind: Kind, val message: String) {
    enum class Kind {
        /** Google is busy or the phone could not start the work in the background: a retry is queued. */
        PAUSED,

        /** The spending cap stopped it; raising the cap continues. */
        CAP,

        /** Needs the reader (key, billing, the audio); starting again as it is won't help. */
        STOPPED,
    }

    fun toText(): String = "${kind.name}\n$message"

    companion object {
        fun parse(text: String): MakerOutcome? {
            val kind = Kind.entries.firstOrNull { it.name == text.substringBefore('\n') } ?: return null
            return MakerOutcome(kind, text.substringAfter('\n', ""))
        }
    }
}

/**
 * Where the background job of one book is. What is on disk (how many sentences, the finished file)
 * is [AudiobookMaker.status]; this is only about the work.
 */
sealed interface AudiobookJobState {
    /** No work: never started, cancelled, or the work was forgotten. */
    data object Idle : AudiobookJobState

    /** Queued, waiting for the network it may use (Wi-Fi unless mobile data is allowed) or for the system. */
    data object Waiting : AudiobookJobState

    /** Running; [progress] is null until the first sentence is done. */
    data class Running(val progress: MakerProgress?) : AudiobookJobState

    /** A retry is queued; [message] says why it waits. */
    data class Paused(val message: String) : AudiobookJobState

    /** Ended without a finished book; [capReached] if raising the cap continues it. */
    data class Stopped(val message: String, val capReached: Boolean) : AudiobookJobState

    /** The last run finished the audiobook. */
    data object Finished : AudiobookJobState
}

/**
 * Makes generated audiobooks in the background, one job per book (starting a book that has a job
 * replaces it). The plan must be frozen first ([AudiobookMaker.freeze]); a job goes on from what
 * is on disk, so starting again after a stop continues.
 */
interface AudiobookScheduler {
    /**
     * Starts or continues the audiobook of [bookHash] with [voice] (the plan's voice wins if it has
     * one: a book is never made of two voices); [capUsd] raises the plan's cap. The job waits for
     * an unmetered network, or any network if [allowMobileData].
     */
    fun start(bookHash: String, voice: String, capUsd: Double, allowMobileData: Boolean)

    /** Stops the job of [bookHash]; what is made stays, and starting again continues. */
    fun cancel(bookHash: String)

    /** The state of the job of [bookHash], following it as it changes. */
    fun state(bookHash: String): Flow<AudiobookJobState>
}
