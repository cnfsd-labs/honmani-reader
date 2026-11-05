package app.honmani.reader.data.merge

/**
 * The time every write stamps into `updated_at` and into deletion markers ([TombstoneEntity]), so a
 * merge can tell which of two phones edited a row last. The DAOs are made by Room and can't take a
 * clock, so tests swap [source] (and put it back).
 */
object UpdateClock {
    @Volatile
    var source: () -> Long = System::currentTimeMillis

    fun now(): Long = source()
}
