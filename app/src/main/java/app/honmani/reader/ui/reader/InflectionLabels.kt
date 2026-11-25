package app.honmani.reader.ui.reader

import androidx.annotation.StringRes
import app.honmani.reader.R
import app.honmani.reader.lookup.Inflection

/** Label for an [Inflection], with a short note on what it means. */
@StringRes
fun Inflection.labelRes(): Int = when (this) {
    Inflection.NEGATIVE -> R.string.inflection_negative
    Inflection.PAST -> R.string.inflection_past
    Inflection.TE -> R.string.inflection_te
    Inflection.POLITE -> R.string.inflection_polite
    Inflection.POTENTIAL -> R.string.inflection_potential
    Inflection.PASSIVE -> R.string.inflection_passive
    Inflection.POTENTIAL_OR_PASSIVE -> R.string.inflection_potential_or_passive
    Inflection.CAUSATIVE -> R.string.inflection_causative
    Inflection.CAUSATIVE_SHORT -> R.string.inflection_causative_short
    Inflection.VOLITIONAL -> R.string.inflection_volitional
    Inflection.IMPERATIVE -> R.string.inflection_imperative
    Inflection.CONDITIONAL_BA -> R.string.inflection_conditional_ba
    Inflection.CONDITIONAL_TARA -> R.string.inflection_conditional_tara
    Inflection.TARI -> R.string.inflection_tari
    Inflection.WANT -> R.string.inflection_want
    Inflection.STEM -> R.string.inflection_stem
    Inflection.PROGRESSIVE -> R.string.inflection_progressive
    Inflection.COMPLETION -> R.string.inflection_completion
    Inflection.PREPARATION -> R.string.inflection_preparation
    Inflection.RESULTING_STATE -> R.string.inflection_resulting_state
    Inflection.ATTEMPT -> R.string.inflection_attempt
    Inflection.GIVING -> R.string.inflection_giving
    Inflection.RECEIVING -> R.string.inflection_receiving
    Inflection.REQUEST -> R.string.inflection_request
    Inflection.DESIRE_OF_OTHERS -> R.string.inflection_desire_of_others
    Inflection.DIRECTION -> R.string.inflection_direction
    Inflection.WHILE -> R.string.inflection_while
    Inflection.COMMAND_POLITE -> R.string.inflection_command_polite
    Inflection.TOO_MUCH -> R.string.inflection_too_much
    Inflection.SEEMINGLY -> R.string.inflection_seemingly
    Inflection.EASY -> R.string.inflection_easy
    Inflection.HARD -> R.string.inflection_hard
    Inflection.WITHOUT -> R.string.inflection_without
    Inflection.ADVERB -> R.string.inflection_adverb
    Inflection.NOUN -> R.string.inflection_noun
    Inflection.SURU_VERB -> R.string.inflection_suru_verb
    Inflection.NEGATIVE_VOLITIONAL -> R.string.inflection_negative_volitional
    Inflection.TE_WA -> R.string.inflection_te_wa
    Inflection.CLASSICAL -> R.string.inflection_classical
    Inflection.ROUGH -> R.string.inflection_rough
    Inflection.CONTEMPT -> R.string.inflection_contempt
}
