package app.honmani.reader.data.opds

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

internal fun OutputStream.head(status: String, vararg headers: String) {
    val lines = listOf("HTTP/1.1 $status") + headers + "Connection: close"
    write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray())
}

internal fun OutputStream.respond(status: String, body: String, vararg headers: String) {
    val bytes = body.toByteArray()
    head(status, *headers, "Content-Length: ${bytes.size}")
    write(bytes)
}

/** A request as [HeaderServer] received it; header names in lower case. */
internal data class ReceivedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * Minimal HTTP/1.1 server on localhost that records each request's Authorization header, and
 * each whole request in [requests].
 */
internal class HeaderServer(private val respond: (path: String, out: OutputStream) -> Unit) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort
    val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val requests: MutableList<ReceivedRequest> = Collections.synchronizedList(mutableListOf())

    private val acceptor = Thread {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            handle(client)
        }
    }.apply {
        isDaemon = true
        start()
    }

    private fun handle(client: Socket) = client.use {
        val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine()?.split(' ') ?: return
        val path = requestLine.getOrNull(1) ?: return
        var authorization: String? = null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            if (line.startsWith("Authorization:", ignoreCase = true)) authorization = line.substringAfter(':').trim()
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        authorizations += authorization
        val bodyText = String(body, 0, read).toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
        requests += ReceivedRequest(requestLine[0], path, headers, bodyText)
        val out = it.getOutputStream().buffered()
        respond(path, out)
        out.flush()
    }

    /** Waits for the accepting thread: until it has left accept(), the port still takes a connection. */
    fun stop() {
        socket.close()
        acceptor.join(1_000)
    }
}
