package io.sodyx.app.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/** Android platform HTTP implementation; it carries no retry, logging, or plaintext handling. */
internal class UrlConnectionRelayHttpExecutor : RelayHttpExecutor {
    override fun execute(endpoint: RelayEndpoint, request: RelayHttpRequest): RelayHttpResponse {
        val uri = URI(endpoint.baseUri.toString().trimEnd('/') + request.path)
        val connection = try {
            (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
                request.authorizationCapability?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
                request.body?.let { body ->
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }
        } catch (error: IOException) {
            throw RelayNetworkException("Relay connection failed.", error)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            RelayHttpResponse(
                status,
                stream?.use {
                    it.readUtf8Bounded(MAX_RESPONSE_BYTES)
                }.orEmpty()
            )
        } catch (error: IOException) {
            throw RelayNetworkException("Relay response failed.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readUtf8Bounded(maxBytes: Int): String {
        val bytes = readBytes()
        if (bytes.size >
            maxBytes
        ) {
            throw RelayNetworkException("Relay response exceeds the configured limit.")
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
