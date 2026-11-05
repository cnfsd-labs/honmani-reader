package app.honmani.reader.data.merge

import org.junit.rules.ExternalResource

/**
 * Holds [UpdateClock] still, for tests that compare whole rows: a row written again (Undo, restore)
 * is stamped anew, which is right, but would never equal the row from before.
 */
class FixedUpdateClock(private val at: Long = 1L) : ExternalResource() {
    override fun before() {
        UpdateClock.source = { at }
    }

    override fun after() {
        UpdateClock.source = System::currentTimeMillis
    }
}
