package tech.relaycorp.poweb

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.readBytes
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.toByteArray
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import org.bouncycastle.util.encoders.Base64
import tech.relaycorp.relaynet.bindings.ContentTypes
import tech.relaycorp.relaynet.bindings.pdc.ClientBindingException
import tech.relaycorp.relaynet.bindings.pdc.DetachedSignatureType
import tech.relaycorp.relaynet.bindings.pdc.NonceSignerException
import tech.relaycorp.relaynet.bindings.pdc.PDCClient
import tech.relaycorp.relaynet.bindings.pdc.ParcelCollection
import tech.relaycorp.relaynet.bindings.pdc.RejectedParcelException
import tech.relaycorp.relaynet.bindings.pdc.ServerBindingException
import tech.relaycorp.relaynet.bindings.pdc.ServerConnectionException
import tech.relaycorp.relaynet.bindings.pdc.ServerException
import tech.relaycorp.relaynet.bindings.pdc.Signer
import tech.relaycorp.relaynet.bindings.pdc.StreamingMode
import tech.relaycorp.relaynet.messages.InvalidMessageException
import tech.relaycorp.relaynet.messages.control.HandshakeChallenge
import tech.relaycorp.relaynet.messages.control.HandshakeResponse
import tech.relaycorp.relaynet.messages.control.ParcelDelivery
import tech.relaycorp.relaynet.messages.control.PrivateNodeRegistration
import tech.relaycorp.relaynet.messages.control.PrivateNodeRegistrationRequest
import tech.relaycorp.relaynet.wrappers.x509.Certificate
import java.io.EOFException
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.time.Duration

/**
 * PoWeb client.
 *
 * @param hostName The IP address or domain for the PoWeb server
 * @param port The port for the PoWeb server
 * @param useTls Whether the PoWeb server uses TLS
 *
 * The underlying connection is created lazily.
 */
@OptIn(KtorExperimentalAPI::class)
public class PoWebClient internal constructor(
    internal val hostName: String,
    internal val port: Int,
    internal val useTls: Boolean,
    ktorEngine: HttpClientEngine = OkHttp.create {
        preconfigured = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .pingInterval(PING_INTERVAL)
            .build()
    }
) : PDCClient {
    internal var ktorClient = HttpClient(ktorEngine) {
        install(WebSockets)
    }

    private val urlScheme = if (useTls) "https" else "http"
    private val wsScheme = if (useTls) "wss" else "ws"

    internal val baseURL: String = "$urlScheme://$hostName:$port/v1"

    /**
     * Close the underlying connection to the server (if any).
     */
    override fun close(): Unit = ktorClient.close()

    /**
     * Request a Private Node Registration Authorization (PNRA).
     *
     * @param nodePublicKey The public key of the private node requesting authorization
     */
    @Throws(
        ServerException::class,
        ClientBindingException::class
    )
    public override suspend fun preRegisterNode(
        nodePublicKey: PublicKey
    ): PrivateNodeRegistrationRequest {
        val keyDigest = getSHA256DigestHex(nodePublicKey.encoded)
        val response =
            post("/pre-registrations", TextContent(keyDigest, PRE_REGISTRATION_CONTENT_TYPE))

        requireContentType(PNRA_CONTENT_TYPE, response.contentType())

        val authorizationSerialized = response.content.toByteArray()
        return PrivateNodeRegistrationRequest(nodePublicKey, authorizationSerialized)
    }

    /**
     * Register a private node.
     *
     * @param pnrrSerialized The Private Node Registration Request
     */
    @Throws(
        ServerException::class,
        ClientBindingException::class
    )
    public override suspend fun registerNode(pnrrSerialized: ByteArray): PrivateNodeRegistration {
        val response = post("/nodes", ByteArrayContent(pnrrSerialized, PNRR_CONTENT_TYPE))

        requireContentType(PNR_CONTENT_TYPE, response.contentType())

        return try {
            PrivateNodeRegistration.deserialize(response.content.toByteArray())
        } catch (exc: InvalidMessageException) {
            throw ServerBindingException("The server returned a malformed registration", exc)
        }
    }

    /**
     * Deliver a parcel.
     *
     * @param parcelSerialized The serialization of the parcel
     * @param deliverySigner The signer to sign this delivery
     */
    @Throws(
        ServerException::class,
        RejectedParcelException::class,
        ClientBindingException::class
    )
    public override suspend fun deliverParcel(parcelSerialized: ByteArray, deliverySigner: Signer) {
        val deliverySignature = deliverySigner.sign(
            parcelSerialized,
            DetachedSignatureType.PARCEL_DELIVERY
        )
        val deliverySignatureBase64 = Base64.toBase64String(deliverySignature)
        val authorizationHeader = "Relaynet-Countersignature $deliverySignatureBase64"
        val body = ByteArrayContent(parcelSerialized, PARCEL_CONTENT_TYPE)
        try {
            post("/parcels", body, authorizationHeader)
        } catch (_: ForbiddenException) {
            throw RejectedParcelException("The server rejected the parcel")
        }
    }

    /**
     * Collect parcels on behalf of the specified nodes.
     *
     * @param nonceSigners The nonce signers for each node whose parcels should be collected
     * @param streamingMode Which streaming mode to ask the server to use
     */
    @Throws(
        ServerException::class,
        ClientBindingException::class,
        NonceSignerException::class
    )
    public override suspend fun collectParcels(
        nonceSigners: Array<Signer>,
        streamingMode: StreamingMode
    ): Flow<ParcelCollection> = flow {
        if (nonceSigners.isEmpty()) {
            throw NonceSignerException("At least one nonce signer must be specified")
        }

        val trustedCertificates = nonceSigners.map { it.certificate }
        val streamingModeHeader = Pair(StreamingMode.HEADER_NAME, streamingMode.headerValue)
        wsConnect(PARCEL_COLLECTION_ENDPOINT_PATH, listOf(streamingModeHeader)) {
            try {
                handshake(nonceSigners)
            } catch (exc: ClosedReceiveChannelException) {
                // Alert the client to the fact that the server closed the connection before
                // completing the handshake. Otherwise, the client will assume that the operation
                // succeeded and there were no parcels to collect.
                throw ServerConnectionException(
                    "Server closed the connection during the handshake",
                    exc
                )
            }
            collectAndAckParcels(this, this@flow, trustedCertificates)

            // The server must've closed the connection for us to get here, since we're consuming
            // all incoming messages indefinitely.
            val reason = closeReason.await()!!
            if (reason.code != CloseReason.Codes.NORMAL.code) {
                throw ServerConnectionException(
                    "Server closed the connection unexpectedly " +
                        "(code: ${reason.code}, reason: ${reason.message})"
                )
            }
        }
    }

    @Throws(ServerBindingException::class)
    private suspend fun collectAndAckParcels(
        webSocketSession: DefaultClientWebSocketSession,
        flowCollector: FlowCollector<ParcelCollection>,
        trustedCertificates: List<Certificate>
    ) {
        for (frame in webSocketSession.incoming) {
            val delivery = try {
                ParcelDelivery.deserialize(frame.readBytes())
            } catch (exc: InvalidMessageException) {
                webSocketSession.close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid parcel delivery")
                )
                throw ServerBindingException("Received invalid message from server", exc)
            }
            val collector = ParcelCollection(delivery.parcelSerialized, trustedCertificates) {
                webSocketSession.outgoing.send(Frame.Text(delivery.deliveryId))
            }
            flowCollector.emit(collector)
        }
    }

    @Throws(
        ServerConnectionException::class,
        ServerBindingException::class,
        ClientBindingException::class,
        ForbiddenException::class
    )
    internal suspend fun post(
        path: String,
        requestBody: OutgoingContent,
        authorizationHeader: String? = null
    ): HttpResponse {
        val url = "$baseURL$path"
        val response: HttpResponse = try {
            ktorClient.post(url) {
                if (authorizationHeader != null) {
                    header("Authorization", authorizationHeader)
                }
                body = requestBody
            }
        } catch (exc: UnknownHostException) {
            throw ServerConnectionException("Failed to resolve DNS for $baseURL", exc)
        } catch (exc: IOException) {
            throw ServerConnectionException("Failed to connect to $url", exc)
        }

        if (response.status.value in 200..299) {
            return response
        }
        throw when (response.status.value) {
            403 -> ForbiddenException("Got an HTTP 403 response")
            in 400..499 -> ClientBindingException(
                "The server reports that the client violated binding (${response.status})"
            )
            in 500..599 -> ServerConnectionException(
                "The server was unable to fulfil the request (${response.status})"
            )
            else -> ServerBindingException("Received unexpected status (${response.status})")
        }
    }

    private fun requireContentType(
        requiredContentType: ContentType,
        actualContentType: ContentType?
    ) {
        if (actualContentType != requiredContentType) {
            throw ServerBindingException(
                "The server returned an invalid Content-Type ($actualContentType)"
            )
        }
    }

    internal suspend fun wsConnect(
        path: String,
        headers: List<Pair<String, String>>? = null,
        block: suspend DefaultClientWebSocketSession.() -> Unit
    ) = try {
        ktorClient.webSocket(
            "$wsScheme://$hostName:$port$path",
            { headers?.forEach { header(it.first, it.second) } },
            block
        )
    } catch (exc: EOFException) {
        throw ServerConnectionException("Connection was closed abruptly", exc)
    } catch (exc: IOException) {
        throw ServerConnectionException("Server is unreachable", exc)
    }

    public companion object {
        internal const val PARCEL_COLLECTION_ENDPOINT_PATH = "/v1/parcel-collection"

        private const val DEFAULT_LOCAL_PORT = 276
        private const val DEFAULT_REMOTE_PORT = 443

        private val PARCEL_CONTENT_TYPE = ContentType.parse(ContentTypes.PARCEL.value)
        private val PRE_REGISTRATION_CONTENT_TYPE =
            ContentType.parse(ContentTypes.NODE_PRE_REGISTRATION.value)
        private val PNRA_CONTENT_TYPE =
            ContentType.parse(ContentTypes.NODE_REGISTRATION_AUTHORIZATION.value)
        private val PNRR_CONTENT_TYPE =
            ContentType.parse(ContentTypes.NODE_REGISTRATION_REQUEST.value)
        private val PNR_CONTENT_TYPE =
            ContentType("application", "vnd.relaynet.node-registration.registration")

        private val PING_INTERVAL = Duration.ofSeconds(5)

        /**
         * Connect to a private gateway from a private endpoint.
         *
         * @param port The port for the PoWeb server
         *
         * TLS won't be used.
         */
        public fun initLocal(port: Int = DEFAULT_LOCAL_PORT): PoWebClient =
            PoWebClient("127.0.0.1", port, false)

        /**
         * Connect to a public gateway from a private gateway via TLS.
         *
         * @param hostName The IP address or domain for the PoWeb server
         * @param port The port for the PoWeb server
         */
        public fun initRemote(hostName: String, port: Int = DEFAULT_REMOTE_PORT): PoWebClient =
            PoWebClient(hostName, port, true)

        private fun getSHA256DigestHex(plaintext: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(plaintext).joinToString("") { "%02x".format(it) }
        }
    }
}

@Throws(ServerBindingException::class)
private suspend fun DefaultClientWebSocketSession.handshake(nonceSigners: Array<Signer>) {
    val challengeRaw = incoming.receive()
    val challenge = try {
        HandshakeChallenge.deserialize(challengeRaw.readBytes())
    } catch (exc: InvalidMessageException) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ""))
        throw ServerBindingException("Server sent an invalid handshake challenge", exc)
    }
    val nonceSignatures =
        nonceSigners.map { it.sign(challenge.nonce, DetachedSignatureType.NONCE) }.toList()
    val response = HandshakeResponse(nonceSignatures)
    outgoing.send(Frame.Binary(true, response.serialize()))
}
