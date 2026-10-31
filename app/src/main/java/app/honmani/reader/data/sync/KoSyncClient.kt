package app.honmani.reader.data.sync

import app.honmani.reader.data.net.LoginGate
import app.honmani.reader.data.net.originOf
import app.honmani.reader.diagnostics.NetworkLog
import app.honmani.reader.diagnostics.NetworkPurpose
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A KOReader progress sync account: the server (KOReader's own sync server, a self-hosted
 * koreader-sync-server, or Calibre-Web Automated's `/kosync`) and the login. [userKey] is the
 * MD5 of the password, which is what KOReader sends and stores instead of the password.
 * [password] is kept only for servers that check the password itself with HTTP Basic
 * (Calibre-Web Automated, whose accounts are its web accounts).
 */
data class KoSyncAccount(
    val server: String,
    val username: String,
    val userKey: String,
    val password: String? = null,
) {
    override fun toString() = "KoSyncAccount(server=$server, username=$username)"

    companion object {
        fun userKey(password: String): String =
            MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** A reading position as the sync server keeps it. */
data class RemoteProgress(
    /** An XPointer for reflowable books ("/body/DocFragment[3]/body/p[7]/text().12"), a page number for PDFs. */
    val progress: String,
    /** 0 to 1. */
    val percentage: Double,
    /** The device's name, as shown to the reader ("Kindle"). */
    val device: String,
    val deviceId: String,
    /** When the server stored it, in seconds since 1970; null for old servers. */
    val timestamp: Long?,
)

class KoSyncException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** Wrong user name or password. */
        UNAUTHORIZED,

        /** No answer, or not a sync server. */
        NETWORK,

        SERVER,

        /** The server asked for the login over http on a hotspot with a login page: not sent. */
        HOTSPOT,
    }
}

/**
 * The KOReader sync API (`plugins/kosync.koplugin/api.json` in KOReader). Accounts are made in
 * KOReader or on the server, never here: Play wants a way to delete an account an app created,
 * and the protocol has none. Over plain http the login waits until the server asks for it
 * ([LoginGate]).
 */
class KoSyncClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val logins: LoginGate = LoginGate(),
) {
    /**
     * Signs in with what the reader typed and returns the account that worked:
     * - KOReader's login (the MD5 key) first, then the password with HTTP Basic, which
     *   Calibre-Web Automated asks for. The password itself only goes to an address ending in
     *   `/kosync` (CWA's), never to KOReader's servers when the key was merely wrong.
     * - An address without a sync server is tried again with `/kosync` (Calibre-Web Automated's
     *   own address, as its KOReader page shows it).
     */
    suspend fun signIn(server: String, username: String, password: String): KoSyncAccount {
        val keyed = KoSyncAccount(server.trim(), username.trim(), KoSyncAccount.userKey(password))
        val base = keyed.server.trimEnd('/')
        val addresses = if (base.endsWith(CWA_PATH)) listOf(keyed.server) else listOf(keyed.server, base + CWA_PATH)
        var failure: KoSyncException? = null
        for (address in addresses) {
            val account = keyed.copy(server = address)
            try {
                authorize(account)
                return account
            } catch (e: KoSyncException) {
                if (e.reason == KoSyncException.Reason.UNAUTHORIZED && address.trimEnd('/').endsWith(CWA_PATH)) {
                    val basic = account.copy(password = password)
                    authorize(basic)
                    return basic
                }
                if (e.reason != KoSyncException.Reason.NETWORK) throw e
                if (failure == null) failure = e
            }
        }
        throw failure!!
    }

    /** Checks the login. */
    suspend fun authorize(account: KoSyncAccount) {
        val (status, _) = request(account, "GET", "/users/auth")
        if (status != 200) throw unexpected(status)
    }

    /** The latest position saved for [document], or null if there is none. */
    suspend fun progress(account: KoSyncAccount, document: String): RemoteProgress? {
        val (status, text) = request(account, "GET", "/syncs/progress/" + URLEncoder.encode(document, "UTF-8"))
        if (status != 200) throw unexpected(status)
        val body = try {
            json.decodeFromString<ProgressBody>(text)
        } catch (e: SerializationException) {
            throw KoSyncException(KoSyncException.Reason.SERVER, "Not a progress answer", e)
        } catch (e: IllegalArgumentException) {
            throw KoSyncException(KoSyncException.Reason.SERVER, "Not a progress answer", e)
        }
        val progress = body.progress ?: return null
        val percentage = body.percentage ?: return null
        return RemoteProgress(
            progress = progress,
            percentage = percentage.coerceIn(0.0, 1.0),
            device = body.device.orEmpty(),
            deviceId = body.deviceId.orEmpty(),
            timestamp = body.timestamp,
        )
    }

    /** Saves this device's position in [document]. */
    suspend fun update(account: KoSyncAccount, document: String, progress: String, percentage: Double, device: String, deviceId: String) {
        val body = buildJsonObject {
            put("document", document)
            put("progress", progress)
            put("percentage", percentage)
            put("device", device)
            put("device_id", deviceId)
        }
        val (status, _) = request(account, "PUT", "/syncs/progress", body.toString())
        if (status != 200 && status != 202) throw unexpected(status)
    }

    private suspend fun request(
        account: KoSyncAccount,
        method: String,
        path: String,
        body: String? = null,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val url = try {
            URL(endpoint(account.server, path))
        } catch (e: IOException) {
            throw KoSyncException(KoSyncException.Reason.NETWORK, "Bad server address", e)
        }
        if (url.protocol != "http" && url.protocol != "https") {
            throw KoSyncException(KoSyncException.Reason.NETWORK, "Not a web address")
        }
        if (logins.maySend(url.toString())) return@withContext send(account, url, method, body, login = true)
        try {
            send(account, url, method, body, login = false)
        } catch (e: KoSyncException) {
            if (e.reason != KoSyncException.Reason.UNAUTHORIZED) throw e
            // An address without a known origin can't be remembered as having asked: no login.
            if (originOf(url.toString()) == null) throw e
            // Over http the login waited for the server to ask: send it now (not on a hotspot).
            if (logins.blocked(url.toString())) {
                throw KoSyncException(KoSyncException.Reason.HOTSPOT, "No login over http on a hotspot")
            }
            logins.asked(url.toString())
            send(account, url, method, body, login = true)
        }
    }

    private fun send(account: KoSyncAccount, url: URL, method: String, body: String?, login: Boolean): Pair<Int, String> {
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw KoSyncException(KoSyncException.Reason.NETWORK, e.message ?: "No connection", e)
        }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            // The login headers must never follow a redirect to another server.
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", ACCEPT)
            if (login && account.password != null) {
                val token = "${account.username}:${account.password}".toByteArray(Charsets.UTF_8)
                connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(token))
            } else if (login) {
                connection.setRequestProperty("x-auth-user", account.username)
                connection.setRequestProperty("x-auth-key", account.userKey)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            NetworkLog.shared.answered(NetworkPurpose.PROGRESS_SYNC, url.toString(), status, connection.contentLengthLong)
            if (status == 401) throw KoSyncException(KoSyncException.Reason.UNAUTHORIZED, "Wrong login")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { input ->
                // Not readNBytes: that needs Android 13.
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_ANSWER_BYTES) throw KoSyncException(KoSyncException.Reason.SERVER, "Answer too large")
                }
                out.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return status to text
        } catch (e: KoSyncException) {
            throw e
        } catch (e: IOException) {
            NetworkLog.shared.failed(NetworkPurpose.PROGRESS_SYNC, url.toString(), e)
            throw KoSyncException(KoSyncException.Reason.NETWORK, e.message ?: "No connection", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun unexpected(status: Int) = KoSyncException(
        if (status == 404 || status in 300..399) KoSyncException.Reason.NETWORK else KoSyncException.Reason.SERVER,
        "The server answered $status",
    )

    @Serializable
    private data class ProgressBody(
        val progress: String? = null,
        val percentage: Double? = null,
        val device: String? = null,
        @kotlinx.serialization.SerialName("device_id") val deviceId: String? = null,
        val timestamp: Long? = null,
    )

    companion object {
        private const val ACCEPT = "application/vnd.koreader.v1+json"
        private const val MAX_ANSWER_BYTES = 64 * 1024
        private const val CWA_PATH = "/kosync"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The server address as typed (with or without a trailing slash, https:// added if missing) plus [path]. */
        internal fun endpoint(server: String, path: String): String {
            val base = server.trim().trimEnd('/')
            val withScheme = if ("://" in base) base else "https://$base"
            return withScheme + path
        }
    }
}
