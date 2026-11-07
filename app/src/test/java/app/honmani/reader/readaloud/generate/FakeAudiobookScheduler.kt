package app.honmani.reader.readaloud.generate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** An [AudiobookScheduler] for UI tests: records what was asked and shows the state a test sets. */
class FakeAudiobookScheduler : AudiobookScheduler {
    data class Start(val bookHash: String, val voice: String, val capUsd: Double, val allowMobileData: Boolean)

    val starts = mutableListOf<Start>()
    val cancels = mutableListOf<String>()
    private val states = HashMap<String, MutableStateFlow<AudiobookJobState>>()

    private fun flow(bookHash: String) = states.getOrPut(bookHash) { MutableStateFlow(AudiobookJobState.Idle) }

    /** Sets what [state] shows for [bookHash]. */
    fun set(bookHash: String, state: AudiobookJobState) {
        flow(bookHash).value = state
    }

    override fun start(bookHash: String, voice: String, capUsd: Double, allowMobileData: Boolean) {
        starts += Start(bookHash, voice, capUsd, allowMobileData)
        set(bookHash, AudiobookJobState.Waiting)
    }

    override fun cancel(bookHash: String) {
        cancels += bookHash
        set(bookHash, AudiobookJobState.Idle)
    }

    override fun state(bookHash: String): Flow<AudiobookJobState> = flow(bookHash)
}
