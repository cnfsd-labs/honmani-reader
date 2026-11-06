package app.honmani.reader.ui

/** The page a re-tap on the open bottom-bar tab goes to: the next one, the first after the last. */
fun nextPageInCycle(current: Int, pages: Int): Int = if (pages <= 0) 0 else (current + 1) % pages
