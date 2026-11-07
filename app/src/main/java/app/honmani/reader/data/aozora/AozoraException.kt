package app.honmani.reader.data.aozora

import java.io.IOException

/** Loading the work list or a book failed; [reason] says what to tell the user. */
class AozoraException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** No connection, or it broke off. */
        NETWORK,

        /** The server answered something else than the file. */
        SERVER,

        /** The file is bigger than the app accepts. */
        TOO_LARGE,

        /** The file is not what it should be (a list or a ZIP that cannot be read). */
        BAD_DATA,

        /** The list is in a newer format than this app reads. */
        TOO_NEW,

        /** A redirect to somewhere the app does not follow (another host, plain http). */
        REFUSED,
    }
}
