package app.honmani.reader.ui

import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The one way a date shows in the app: the locale's medium style ("Nov 16, 2026", "2026/11/16"),
 * so a milestone, a book's dates and the statistics read alike.
 */
fun mediumDateFormat(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
