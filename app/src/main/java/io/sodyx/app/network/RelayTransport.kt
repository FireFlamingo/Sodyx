package io.sodyx.app.network

import java.net.URI
import java.util.Base64
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Client boundary for the Phase 8 relay.
 *
 * This package accepts ciphertext envelopes only. It deliberately has no message, contact, alias,
 * or plaintext type in its public API. A relay is an availability component, not an anonymity
 * guarantee: it can still observe the time, size, and mailbox capability used for a request.
 */
interface RelayTransport {
    suspend fun createMailbox(ttlSeconds: Int = DEFAULT_MAILBOX_TTL_SECONDS): RelayMailboxProvision

    suspend fun send(destination: RelayDeliveryMailbox, envelope: OpaqueEnvelope): RelayDeliveryId

    suspend fun receive(source: RelayRetrievalMailbox): List<RelayReceivedEnvelope>

    /** Acknowledges only an already retrieved ciphertext record. A missing/expired record is idempotent. */
    suspend fun acknowledge(source: RelayRetrievalMailbox, id: RelayDeliveryId)

    suspend fun revoke(source: RelayRetrievalMailbox)
}

/** Delivery and retrieval capabilities are separate so callers cannot use one in the wrong direction. */
class RelayMailboxProvision internal constructor(
    val delivery: RelayDeliveryMailbox,
    val retrieval: RelayRetrievalMailbox,
    val expiresAtEpochMillis: Long
)

class RelayDeliveryMailbox internal constructor(
    private val capability: String,
    internal val expiresAtEpochMillis: Long
) {
    /** Use only when encrypting an authenticated setup/control message for the recipient. */
    fun encodedForEncryptedControl(): String = capability

    internal fun authorizationValue(): String = capability

    override fun toString(): String = "RelayDeliveryMailbox(redacted)"
}

class RelayRetrievalMailbox internal constructor(
    private val capability: String,
    internal val expiresAtEpochMillis: Long
) {
    internal fun authorizationValue(): String = capability

    override fun toString(): String = "RelayRetrievalMailbox(redacted)"
}

/**
 * Opaque bytes produced by the E2EE/framing layer. The transport copies bytes at its boundary so
 * later caller mutation cannot change the request in flight.
 */
class OpaqueEnvelope private constructor(private val contents: ByteArray) {
    val size: Int
        get() = contents.size

    fun copyBytes(): ByteArray = contents.copyOf()

    internal fun encoded(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(contents)

    override fun toString(): String = "OpaqueEnvelope(size=${contents.size})"

    companion object {
        fun fromEncryptedBytes(bytes: ByteArray): OpaqueEnvelope {
            require(bytes.isNotEmpty()) { "An encrypted envelope must not be empty." }
            require(bytes.size <= MAX_ENVELOPE_BYTES) {
                "An encrypted envelope must not exceed $MAX_ENVELOPE_BYTES bytes."
            }
            return OpaqueEnvelope(bytes.copyOf())
        }
    }
}

class RelayDeliveryId internal constructor(internal val value: String) {
    override fun equals(other: Any?): Boolean = other is RelayDeliveryId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "RelayDeliveryId(redacted)"
}

class RelayReceivedEnvelope internal constructor(
    val id: RelayDeliveryId,
    val envelope: OpaqueEnvelope
)

/** HTTPS endpoint. Cleartext is permitted only for explicit loopback test fixtures. */
class RelayEndpoint private constructor(internal val baseUri: URI) {
    override fun toString(): String =
        "RelayEndpoint(${baseUri.scheme}://${baseUri.host}${baseUri.path})"

    companion object {
        fun production(value: String): RelayEndpoint =
            create(value, permitLoopbackHttpForTests = false)

        fun loopbackFixtureForTests(value: String): RelayEndpoint =
            create(value, permitLoopbackHttpForTests = true)

        private fun create(value: String, permitLoopbackHttpForTests: Boolean): RelayEndpoint {
            val uri = try {
                URI(value)
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "Relay endpoint must be a valid absolute URI.",
                    error
                )
            }
            require(uri.isAbsolute && uri.host != null) {
                "Relay endpoint must have a scheme and host."
            }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Relay endpoint must not contain credentials, a query, or a fragment."
            }
            val isHttps = uri.scheme.equals("https", ignoreCase = true)
            val isTestLoopbackHttp =
                permitLoopbackHttpForTests && uri.scheme.equals("http", ignoreCase = true) &&
                    uri.host.lowercase() in LOOPBACK_HOSTS
            require(isHttps || isTestLoopbackHttp) {
                "Relay endpoint must use HTTPS; HTTP is allowed only for explicit loopback test fixtures."
            }
            return RelayEndpoint(uri.normalize())
        }
    }
}

internal class HttpRelayTransport(
    private val endpoint: RelayEndpoint,
    private val executor: RelayHttpExecutor = UrlConnectionRelayHttpExecutor(),
    private val clock: RelayClock = SystemRelayClock,
    private val retryPolicy: RelayRetryPolicy = RelayRetryPolicy()
) : RelayTransport {
    override suspend fun createMailbox(ttlSeconds: Int): RelayMailboxProvision {
        require(ttlSeconds in MIN_MAILBOX_TTL_SECONDS..MAX_MAILBOX_TTL_SECONDS) {
            "Mailbox TTL must be between $MIN_MAILBOX_TTL_SECONDS and $MAX_MAILBOX_TTL_SECONDS seconds."
        }
        val response = execute(
            RelayHttpRequest.post("/v1/mailboxes", "{\"ttlSeconds\":$ttlSeconds}")
        )
        requireStatus(response, 201)
        val delivery = RelayJson.requiredToken(
            response.body,
            "deliveryCapability",
            CAPABILITY_LENGTH
        )
        val retrieval = RelayJson.requiredToken(
            response.body,
            "retrievalCapability",
            CAPABILITY_LENGTH
        )
        val expiresAt = RelayJson.requiredLong(response.body, "expiresAt")
        val now = clock.currentTimeMillis()
        require(expiresAt in (now + 1)..(now + ttlSeconds * 1000L + 60_000L)) {
            "Relay returned an invalid mailbox lifetime."
        }
        return RelayMailboxProvision(
            delivery = RelayDeliveryMailbox(delivery, expiresAt),
            retrieval = RelayRetrievalMailbox(retrieval, expiresAt),
            expiresAtEpochMillis = expiresAt
        )
    }

    override suspend fun send(
        destination: RelayDeliveryMailbox,
        envelope: OpaqueEnvelope
    ): RelayDeliveryId {
        requireUsable(destination.expiresAtEpochMillis)
        val response = execute(
            RelayHttpRequest.post(
                path = "/v1/envelopes",
                body = "{\"envelope\":\"${envelope.encoded()}\"}",
                authorizationCapability = destination.authorizationValue()
            )
        )
        requireStatus(response, 201)
        return RelayDeliveryId(RelayJson.requiredToken(response.body, "id", DELIVERY_ID_LENGTH))
    }

    override suspend fun receive(source: RelayRetrievalMailbox): List<RelayReceivedEnvelope> {
        requireUsable(source.expiresAtEpochMillis)
        val response = execute(RelayHttpRequest.get("/v1/envelopes", source.authorizationValue()))
        requireStatus(response, 200)
        return RelayJson.envelopes(response.body).map { item ->
            RelayReceivedEnvelope(
                id = RelayDeliveryId(item.id),
                envelope = OpaqueEnvelope.fromEncryptedBytes(
                    try {
                        Base64.getUrlDecoder().decode(item.envelope)
                    } catch (error: IllegalArgumentException) {
                        throw RelayProtocolException(
                            "Relay returned an invalid envelope encoding.",
                            error
                        )
                    }
                )
            )
        }
    }

    override suspend fun acknowledge(source: RelayRetrievalMailbox, id: RelayDeliveryId) {
        requireUsable(source.expiresAtEpochMillis)
        val response = execute(
            RelayHttpRequest.delete("/v1/envelopes/${id.value}", source.authorizationValue())
        )
        if (response.status !in setOf(204, 404)) {
            requireStatus(response, 204)
        }
    }

    override suspend fun revoke(source: RelayRetrievalMailbox) {
        val response = execute(
            RelayHttpRequest.delete("/v1/mailboxes", source.authorizationValue())
        )
        if (response.status !in setOf(204, 404)) requireStatus(response, 204)
    }

    private fun requireUsable(expiresAtEpochMillis: Long) {
        check(clock.currentTimeMillis() < expiresAtEpochMillis) {
            "Mailbox capability has expired locally."
        }
    }

    private suspend fun execute(request: RelayHttpRequest): RelayHttpResponse {
        var attempt = 0
        while (true) {
            try {
                val response = withContext(Dispatchers.IO) { executor.execute(endpoint, request) }
                if (!response.isTransient() || attempt >= retryPolicy.maxRetries) return response
            } catch (error: RelayNetworkException) {
                if (attempt >= retryPolicy.maxRetries) throw error
            }
            attempt += 1
            delay(retryPolicy.delayForRetry(attempt))
        }
    }
}

internal interface RelayHttpExecutor {
    @Throws(RelayNetworkException::class)
    fun execute(endpoint: RelayEndpoint, request: RelayHttpRequest): RelayHttpResponse
}

internal data class RelayHttpRequest(
    val method: String,
    val path: String,
    val body: String?,
    val authorizationCapability: String?
) {
    override fun toString(): String =
        "RelayHttpRequest(method=$method, path=$path, payload=<redacted>)"
    init {
        require(path.startsWith('/') && !path.contains('?') && !path.contains('#')) {
            "Invalid relay path."
        }
    }

    companion object {
        fun post(path: String, body: String, authorizationCapability: String? = null) =
            RelayHttpRequest("POST", path, body, authorizationCapability)

        fun get(path: String, authorizationCapability: String) =
            RelayHttpRequest("GET", path, null, authorizationCapability)

        fun delete(path: String, authorizationCapability: String) =
            RelayHttpRequest("DELETE", path, null, authorizationCapability)
    }
}

internal data class RelayHttpResponse(val status: Int, val body: String) {
    override fun toString(): String = "RelayHttpResponse(status=$status, body=<redacted>)"
    fun isTransient(): Boolean = status == 429 || status in 500..599
}

class RelayNetworkException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class RelayProtocolException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class RelayServerException internal constructor(val status: Int) :
    RuntimeException("Relay request failed with HTTP $status.")

data class RelayRetryPolicy(val maxRetries: Int = 2, val initialDelayMillis: Long = 200) {
    init {
        require(maxRetries in 0..3) { "Relay retries must be bounded to at most three." }
        require(initialDelayMillis in 0..5_000) { "Invalid relay retry delay." }
    }

    internal fun delayForRetry(attempt: Int): Long =
        min(initialDelayMillis * (1L shl (attempt - 1)), 5_000)
}

internal interface RelayClock {
    fun currentTimeMillis(): Long
}

internal object SystemRelayClock : RelayClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

private fun requireStatus(response: RelayHttpResponse, expected: Int) {
    if (response.status != expected) throw RelayServerException(response.status)
}

private object RelayJson {
    private val tokenPattern = Regex("^[A-Za-z0-9_-]+$")

    fun requiredToken(json: String, name: String, length: Int): String {
        val value = requiredString(json, name)
        if (value.length != length || !tokenPattern.matches(value)) {
            throw RelayProtocolException("Relay returned an invalid $name.")
        }
        return value
    }

    fun requiredLong(json: String, name: String): Long {
        val value = objectResponse(json)[name] as? JsonPrimitive
        return value?.takeIf { !it.isString }?.longOrNull
            ?: throw RelayProtocolException("Relay returned an invalid $name.")
    }

    fun envelopes(json: String): List<RelayJsonEnvelope> {
        val root = objectResponse(json)
        val array = root["envelopes"] as? JsonArray
            ?: throw RelayProtocolException("Relay returned an invalid envelopes response.")
        if (root.keys != setOf("envelopes") || array.size > MAX_RECEIVE_ENVELOPES) {
            throw RelayProtocolException("Relay returned an invalid envelopes response.")
        }
        val items = array.map { element ->
            val item = element as? JsonObject
                ?: throw RelayProtocolException("Relay returned an invalid envelope.")
            if (item.keys != setOf("id", "envelope")) {
                throw RelayProtocolException("Relay returned an invalid envelope.")
            }
            val id = requiredToken(item.toString(), "id", DELIVERY_ID_LENGTH)
            val envelope = requiredString(item.toString(), "envelope")
            if (envelope.length > 349_526 || !tokenPattern.matches(envelope)) {
                throw RelayProtocolException("Relay returned an invalid envelope encoding.")
            }
            val decoded = try {
                Base64.getUrlDecoder().decode(envelope)
            } catch (_: IllegalArgumentException) {
                throw RelayProtocolException("Relay returned an invalid envelope encoding.")
            }
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != envelope) {
                throw RelayProtocolException("Relay returned an invalid envelope encoding.")
            }
            RelayJsonEnvelope(id, envelope)
        }
        if (items.map { it.id }.distinct().size != items.size) {
            throw RelayProtocolException("Relay returned duplicate envelope identifiers.")
        }
        return items
    }

    private fun requiredString(json: String, name: String): String {
        val value = objectResponse(json)[name] as? JsonPrimitive
        return value?.takeIf { it.isString }?.content
            ?: throw RelayProtocolException("Relay returned an invalid $name.")
    }

    private fun objectResponse(json: String): JsonObject = try {
        Json.parseToJsonElement(json) as? JsonObject
            ?: throw RelayProtocolException("Relay returned invalid JSON.")
    } catch (error: IllegalArgumentException) {
        throw RelayProtocolException("Relay returned invalid JSON.", error)
    }
}

private data class RelayJsonEnvelope(val id: String, val envelope: String)

internal const val MIN_MAILBOX_TTL_SECONDS = 60
internal const val MAX_MAILBOX_TTL_SECONDS = 604_800
internal const val DEFAULT_MAILBOX_TTL_SECONDS = 86_400
internal const val MAX_ENVELOPE_BYTES = 262_144
internal const val MAX_RECEIVE_ENVELOPES = 128
private const val CAPABILITY_LENGTH = 43
private const val DELIVERY_ID_LENGTH = 22
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
