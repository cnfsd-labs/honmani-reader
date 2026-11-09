package app.honmani.reader.readaloud.generate

import app.honmani.reader.anki.CloudVoiceInfo
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What making an audiobook with a Google Cloud voice costs, worked out before anything is sent.
 * Google bills the account of the reader's own API key for every character sent; Honmani only
 * estimates (it can't see the account's usage) and keeps a spending cap per book.
 */
object GeneratedAudiobook {
    /**
     * USD per million characters by voice model (the part of a voice name between the language and
     * the voice, `CloudVoiceInfo.model`), matched by prefix, case-insensitively.
     *
     * As of 2026-11, checked against Google's Cloud Text-to-Speech pricing page; it does not update
     * itself, so VERIFY it before relying on it: prices change (the voice settings say so too). A
     * model not listed (such as Chirp-HD) has no estimate; the reader must give a cap anyway.
     */
    val PRICES: List<Pair<String, Double>> = listOf(
        "Chirp3-HD" to 30.0,
        "Neural2" to 16.0,
        "Wavenet" to 4.0,
        "Standard" to 4.0,
    )

    /**
     * Free characters each month by voice model, in millions, matched like [PRICES]; as of 2026-11,
     * keep it with [PRICES]. Never subtracted from an estimate, since Honmani can't see what is used.
     */
    val FREE_MONTHLY_MILLIONS: List<Pair<String, Int>> = listOf(
        "Chirp3-HD" to 1,
        "Neural2" to 1,
        "Wavenet" to 4,
        "Standard" to 4,
    )

    /** The size of the audio the service returns: 32 kbps MP3, 4,000 bytes a second. */
    const val BYTES_PER_SECOND = 4_000

    /** Spoken Japanese, roughly: about 420 characters a minute. Only for showing a length and size. */
    const val CHARS_PER_SECOND = 7.0

    /** Free space wanted beyond the audio's size: 20%. */
    const val SPACE_MARGIN = 1.2

    /** USD per million characters for [voice] (a name like "ja-JP-Chirp3-HD-Aoede"), or null for a model without a known price. */
    fun pricePerMillion(voice: String): Double? {
        val model = CloudVoiceInfo(voice, null).model
        return PRICES.firstOrNull { model.startsWith(it.first, ignoreCase = true) }?.second
    }

    /** Free characters per month, in millions, for [voice], or null for a model without a known allowance. */
    fun freeMonthlyMillions(voice: String): Int? {
        val model = CloudVoiceInfo(voice, null).model
        return FREE_MONTHLY_MILLIONS.firstOrNull { model.startsWith(it.first, ignoreCase = true) }?.second
    }

    /** The cost in USD of speaking [chars] characters with [voice], or null if its price is not known. */
    fun estimateUsd(chars: Long, voice: String): Double? {
        val price = pricePerMillion(voice) ?: return null
        return BigDecimal(chars).multiply(BigDecimal.valueOf(price)).divide(MILLION).toDouble()
    }

    /** About how long the audio of [chars] spoken characters is, in seconds. */
    fun estimatedSeconds(chars: Long): Long = Math.round(chars / CHARS_PER_SECOND)

    /** About how big the audio of [chars] spoken characters is, in bytes. */
    fun estimatedBytes(chars: Long): Long = estimatedSeconds(chars) * BYTES_PER_SECOND

    /** Free space to ask for before starting: the audio's size and a margin. */
    fun spaceNeededBytes(chars: Long): Long = Math.round(estimatedBytes(chars) * SPACE_MARGIN)

    /** The cap offered at first: the estimate plus 10%, rounded up to a cent; null if there is no estimate. */
    fun defaultCapUsd(chars: Long, voice: String): Double? {
        val estimate = estimateUsd(chars, voice) ?: return null
        return BigDecimal.valueOf(estimate).multiply(BigDecimal("1.1")).setScale(2, RoundingMode.CEILING).toDouble()
    }

    /** Whether [chars] characters with [voice] would cost more than [capUsd]. A voice without a price never does (nothing is known). */
    fun exceedsCap(chars: Long, voice: String, capUsd: Double): Boolean = (estimateUsd(chars, voice) ?: return false) > capUsd

    /**
     * Whether the job may start: a cap above zero is required (also for a voice without a known
     * price), and the estimate must not be over it.
     */
    fun canStart(chars: Long, voice: String, capUsd: Double?): Boolean =
        capUsd != null && capUsd > 0 && !exceedsCap(chars, voice, capUsd)

    private val MILLION = BigDecimal(1_000_000)
}
