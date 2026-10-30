package app.honmani.reader.data

import app.honmani.reader.diagnostics.logWarning
import java.io.File

private const val TAG = "Honmani.files"

/**
 * Writes [file] so that a reader (or a crash half way) sees either the old content or the new,
 * never half of it: [write] fills `<file>.tmp`, which then replaces [file]. The parent directory
 * is created. An exception from [write] removes the temporary file and is passed on.
 *
 * @return false (and a log line) if the rename failed; [file] is then still the old content
 */
fun writeAtomically(file: File, write: (File) -> Unit): Boolean {
    file.parentFile?.mkdirs()
    val temporary = File(file.path + ".tmp")
    try {
        write(temporary)
    } catch (e: Exception) {
        temporary.delete()
        throw e
    } catch (e: Error) {
        temporary.delete()
        throw e
    }
    if (temporary.renameTo(file)) return true
    warn("Could not replace ${file.name}")
    temporary.delete()
    return false
}

/** [writeAtomically] for text. */
fun writeTextAtomically(file: File, text: String): Boolean = writeAtomically(file) { it.writeText(text) }

/**
 * Reads [file] with [parse]; [default] if it does not exist, or (with a log line, since that
 * means lost data) if it cannot be read or parsed.
 */
inline fun <T> readStored(file: File, default: T, parse: (File) -> T): T {
    if (!file.exists()) return default
    return try {
        parse(file)
    } catch (e: Exception) {
        warnUnreadable(file, e)
        default
    }
}

@PublishedApi
internal fun warnUnreadable(file: File, e: Exception) = warn("Could not read ${file.name}: ${e.javaClass.simpleName}")

private fun warn(message: String) = logWarning(TAG, message)

