package dev.convex.android

import android.content.Context
import android.util.Log
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

@PublishedApi
internal val jsonApi = Json {
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
    // This allows Int, Long, Float or Double values anywhere to be annotated with @ConvexNum which
    // knows the special JSON format that Convex uses for those values.
    serializersModule =
        SerializersModule {
            contextual(Int64ToIntDecoder)
            contextual(Int64ToLongDecoder)
            contextual(Float64ToFloatDecoder)
            contextual(Float64ToDoubleDecoder)
        }
}

typealias Int64 = @Serializable(Int64ToLongDecoder::class) Long
typealias Float64 = @Serializable(Float64ToDoubleDecoder::class) Double
typealias Int32 = @Serializable(Int64ToIntDecoder::class) Int
typealias Float32 = @Serializable(Float64ToFloatDecoder::class) Float
typealias ConvexNum = Contextual

/**
 * A client API for interacting with a Convex backend.
 *
 * Handles marshalling of data between calling code and the [convex-mobile]() and
 * [convex-rs](https://github.com/get-convex/convex-rs) native libraries.
 *
 * Consumers of this client should use Kotlin's JSON serialization to handle data sent to/from the
 * Convex backend.
 *
 * Each client owns a WebSocket and a Rust runtime, so an app should create one and share it. It is
 * safe to use from any thread.
 */
open class ConvexClient(
    deploymentUrl: String,
    ffiClientFactory: (deploymentUrl: String, clientId: String, webSocketStateSubscriber: WebSocketStateSubscriber?) -> MobileConvexClientInterface = ::MobileConvexClient
) {
    private val _webSocketStateFlow = MutableStateFlow(WebSocketState.CONNECTING)

    @PublishedApi
    internal val ffiClient =
        ffiClientFactory(
            deploymentUrl,
            "kotlin-${BuildConfig.LIBRARY_VERSION}",
            WebSocketStateAdapter(_webSocketStateFlow)
        )

    /**
     * A [Flow] of [WebSocketState].
     *
     * Will change states as the underlying state of the WebSocket connection to Convex changes.
     */
    val webSocketStateFlow: StateFlow<WebSocketState> = _webSocketStateFlow.asStateFlow()

    /**
     * Subscribes to the query with the given [name] and converts data from the subscription into a
     * [Flow] of [Result]s containing [T].
     *
     * If there is an error generating [T], the [Result] will contain either a [ConvexError] (for
     * application specific errors), a [ServerError], or an [InternalError] when the result doesn't
     * decode as [T]. Errors are values: the subscription stays open and delivers the next result
     * when the query's data changes. The [Flow] only fails if the subscription can't be started.
     *
     * Results are conflated: a collector that falls behind skips to the newest result.
     *
     * The upstream Convex subscription will be canceled if whatever is subscribed to the [Flow]
     * stops listening.
     *
     * @param T result data type that will be decoded from JSON
     */
    inline fun <reified T> subscribe(
        name: String, args: Map<String, Any?>? = null
    ): Flow<Result<T>> = subscribeDecoding(name, args) { value -> jsonApi.decodeFromString<T>(value) }

    @PublishedApi
    internal fun <T> subscribeDecoding(
        name: String,
        args: Map<String, Any?>?,
        decode: (String) -> T
    ): Flow<Result<T>> = callbackFlow {
        val subscription = try {
            ffiClient.subscribe(
                name,
                args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf(),
                SubscriptionAdapter(channel, decode)
            )
        } catch (e: ClientException) {
            throw e.toError()
        }

        awaitClose {
            subscription.cancel()
        }
    }.buffer(Channel.CONFLATED)

    /**
     * Executes the action with the given [name] and [args] and returns the result.
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * For actions that don't return a value, prefer calling the version of this method that doesn't
     * require a return type parameter.
     *
     * @param T data type that will be decoded from JSON and returned
     */
    suspend inline fun <reified T> action(name: String, args: Map<String, Any?>? = null): T {
        try {
            val jsonData = ffiClient.action(name,
                args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf())
            try {
                return jsonApi.decodeFromString<T>(jsonData)
            } catch (e: Throwable) {
                throw InternalError(
                    "Failed to decode JSON, ensure you're using types compatible with Convex in your return value",
                    e
                )
            }
        } catch (e: ClientException) {
            throw e.toError()
        }
    }

    /**
     * Executes the action with the given [name] and [args] and returns the result.
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * This version requires that the remote action returns null or no value. If you wish to
     * return a value from an action, use the version of the method that allows specifying the
     * return type.
     */
    @JvmName("voidAction")
    suspend fun action(name: String, args: Map<String, Any?>? = null) {
        action<Unit?>(name = name, args = args)
    }

    /**
     * Executes the mutation with the given [name] and [args] and returns the result.
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * For actions that don't return a value, prefer calling the version of this method that doesn't
     * require a return type parameter.
     *
     * @param T data type that will be decoded from JSON and returned
     */
    suspend inline fun <reified T> mutation(name: String, args: Map<String, Any?>? = null): T {
        try {
            val jsonData = ffiClient.mutation(name,
                args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf())
            try {
                return jsonApi.decodeFromString<T>(jsonData)
            } catch (e: Throwable) {
                throw InternalError(
                    "Failed to decode JSON, ensure you're using types compatible with Convex in your return value",
                    e
                )
            }
        } catch (e: ClientException) {
            throw e.toError()
        }
    }

    /**
     * Executes the mutation with the given [name] and [args].
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * This version requires that the remote mutation returns null or no value. If you wish to
     * return a value from a mutation, use the version of the method that allows specifying the
     * return type.
     */
    @JvmName("voidMutation")
    suspend fun mutation(name: String, args: Map<String, Any?>? = null) {
        mutation<Unit?>(name = name, args = args)
    }

    /**
     * Authenticates this client with a deployment admin key, acting as [identity] when one is
     * given.
     *
     * For local development only: an admin key has full access to its deployment, so never ship
     * one in an app. Functions see [identity] through `ctx.auth.getUserIdentity()`. This is the
     * counterpart of the JavaScript client's `setAdminAuth(adminKey, identity)`, and it replaces
     * any auth set earlier.
     *
     * @throws InternalError if [identity] has neither a [UserIdentity.tokenIdentifier] nor both an
     *   [UserIdentity.issuer] and a [UserIdentity.subject].
     */
    open suspend fun setAdminAuth(adminKey: String, identity: UserIdentity? = null) {
        val identityJson = identity?.let { jsonApi.encodeToString(UserIdentity.serializer(), it) }
        try {
            ffiClient.setAdminAuth(adminKey, identityJson)
        } catch (e: ClientException) {
            throw e.toError()
        }
    }
}

/**
 * Hands a subscription's results to its [Flow]'s channel.
 *
 * The Rust client calls these methods on its own threads, also after the [Flow] was cancelled.
 * They must not throw: uniffi turns an exception into a panic that silently ends the
 * subscription. So every result, decoding failures included, becomes a value sent to the channel,
 * and results that arrive after the channel closed are dropped (get-convex/convex-mobile#24; its
 * crash is a collector rethrowing such a value, see SubscriptionDeliveryTest).
 */
internal class SubscriptionAdapter<T>(
    private val channel: SendChannel<Result<T>>,
    private val decode: (String) -> T
) : QuerySubscriber {
    override fun onUpdate(value: String) {
        if (channel.isClosedForSend) return
        val result = try {
            Result.success(decode(value))
        } catch (e: Throwable) {
            Result.failure(
                InternalError(
                    "Failed to decode JSON, ensure you're using types compatible with Convex in your return value",
                    e
                )
            )
        }
        channel.trySend(result)
    }

    override fun onError(message: String, value: String?) {
        if (channel.isClosedForSend) return
        // A value is an application specific error thrown in a Convex backend function; without
        // one it's a server error of some sort.
        val error = if (value == null) ServerError(message) else ConvexError(message, value)
        channel.trySend(Result.failure(error))
    }
}

/**
 * Publishes the Rust client's WebSocket state.
 *
 * The Rust client keeps this object for as long as it lives, so it must not reference the
 * [ConvexClient]: through uniffi's handle map that would keep a discarded client, its Rust runtime
 * and its WebSocket alive until the process exits.
 */
private class WebSocketStateAdapter(private val state: MutableStateFlow<WebSocketState>) :
    WebSocketStateSubscriber {
    override fun onStateChange(state: WebSocketState) {
        this.state.value = state
    }
}

/**
 * Like [ConvexClient], but supports integration with an authentication provider via [AuthProvider].
 *
 * The client calls the [AuthProvider] from arbitrary threads, sometimes concurrently (for example a
 * token refresh requested by the Rust client while [login] runs), so providers must be thread-safe.
 *
 * @param T the data returned from the [AuthProvider] upon successful authentication
 * @param coroutineScope where tokens the [AuthProvider] pushes to `onIdToken` are handed to the
 *   Rust client
 */
class ConvexClientWithAuth<T>(
    deploymentUrl: String,
    private val authProvider: AuthProvider<T>,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    ffiClientFactory: (deploymentUrl: String, clientId: String, webSocketSocketStateSubscriber: WebSocketStateSubscriber?) -> MobileConvexClientInterface = ::MobileConvexClient
) : ConvexClient(deploymentUrl, ffiClientFactory) {
    private val _authState = MutableStateFlow<AuthState<T>>(AuthState.Unauthenticated())

    /**
     * The one [AuthTokenProvider] this client ever hands to the Rust client, so the object the Rust
     * client calls into is never swapped while a call is in flight. It refreshes tokens through
     * the [AuthProvider] when the Rust client asks for a fresh one (on reconnect, or after the
     * backend rejected a token).
     */
    private val authBridge = AuthTokenProviderBridge(refreshThrough(WeakReference(this), authProvider))

    /**
     * Orders installing and removing the bridge with the session checks that decide them, so a
     * login, a logout and a pushed token can't interleave into a state none of them meant.
     */
    private val authMutex = Mutex()

    /**
     * A [Flow] of [AuthState] that represents the authentication state of this client instance.
     */
    val authState: StateFlow<AuthState<T>> = _authState.asStateFlow()

    /**
     * Triggers a UI driven login flow and updates the [authState].
     *
     * The [authState] is set to [AuthState.AuthLoading] immediately upon calling this method and
     * will change to either [AuthState.Authenticated] or [AuthState.Unauthenticated] depending on
     * the result. A [logout] that completes while the login flow is still running wins: the login
     * then fails with a [CancellationException] and leaves the client logged out.
     */
    suspend fun login(context: Context): Result<T> =
        loginWithStrategy { cb -> authProvider.login(context, cb) }

    /**
     * Triggers a cached, UI-less re-authentication flow using previously stored credentials and
     * updates the [authState].
     *
     * If no credentials were previously stored, or if there is an error reusing stored credentials,
     * the resulting [authState] will be [AuthState.Unauthenticated]. If supported by the
     * [AuthProvider], a call to [login] should store another set of credentials upon successful
     * authentication.
     *
     * The [authState] is set to [AuthState.AuthLoading] immediately upon calling this method and
     * will change to either [AuthState.Authenticated] or [AuthState.Unauthenticated] depending on
     * the result. Like [login], it loses to a [logout] that completes first.
     */
    suspend fun loginFromCache(): Result<T> =
        loginWithStrategy { cb -> authProvider.loginFromCache(cb) }

    /**
     * Triggers a logout flow and updates the [authState].
     *
     * The [authState] will change to [AuthState.Unauthenticated] if logout is successful. This
     * also clears auth set with [setAdminAuth]. Tokens the [AuthProvider] pushes to an earlier
     * `onIdToken` callback afterwards are ignored.
     */
    suspend fun logout(context: Context): Result<Void?> {
        val result = authProvider.logout(context)
        if (result.isFailure) return result
        return authMutex.withLock {
            authBridge.endSession()
            try {
                ffiClient.setAuthCallback(null)
                result
            } catch (e: ClientException) {
                Result.failure(e.toError())
            } finally {
                _authState.value = AuthState.Unauthenticated()
            }
        }
    }

    /**
     * See [ConvexClient.setAdminAuth].
     *
     * Also ends any session started with [login] or [loginFromCache], so a late token refresh
     * can't replace the admin auth. [authState] doesn't track admin auth, and [logout] clears it.
     */
    override suspend fun setAdminAuth(adminKey: String, identity: UserIdentity?) {
        authMutex.withLock {
            authBridge.endSession()
            super.setAdminAuth(adminKey, identity)
        }
    }

    private suspend fun loginWithStrategy(
        strategy: suspend (onIdToken: (String?) -> Unit) -> Result<T>
    ): Result<T> {
        _authState.value = AuthState.AuthLoading()
        val session = authBridge.currentSession
        try {
            val authData = strategy(onIdTokenHandler(session)).getOrElse { error ->
                _authState.value = AuthState.Unauthenticated()
                return Result.failure(error)
            }
            val token = authProvider.extractIdToken(authData)
            return authMutex.withLock {
                if (!authBridge.activate(token, session)) {
                    // A logout won while the login flow ran; it has published Unauthenticated.
                    return@withLock Result.failure(CancellationException("Logged out during login"))
                }
                try {
                    ffiClient.setAuthCallback(authBridge)
                } catch (e: ClientException) {
                    authBridge.endSession(session)
                    throw e.toError()
                }
                _authState.value = AuthState.Authenticated(authData)
                Result.success(authData)
            }
        } catch (e: Throwable) {
            _authState.value = AuthState.Unauthenticated()
            // Cancellation and errors (such as the NotImplementedError of a provider without
            // loginFromCache) propagate; other failures become the result.
            if (e is CancellationException || e !is Exception) throw e
            Log.e("ConvexClientWithAuth", "Login failed", e)
            return Result.failure(e)
        }
    }

    /**
     * Creates the callback a login of [session] hands the [AuthProvider] for token updates.
     *
     * The provider should call it whenever a fresh token is available, or with `null` when the
     * session becomes invalid. It only affects [session], it applies tokens in the order they are
     * pushed, and it holds the client weakly (see [refreshThrough]).
     */
    private fun onIdTokenHandler(session: Long): (String?) -> Unit {
        val client = WeakReference(this)
        val scope = coroutineScope
        return handler@{ token ->
            val self = client.get() ?: return@handler
            // The session the bridge is in after this push, if the push changed anything.
            val changed = when {
                token != null -> session.takeIf { self.authBridge.update(token, session) }
                self.authBridge.endSession(session) -> session + 1
                else -> null
            } ?: return@handler
            scope.launch { client.get()?.syncAuthCallback(changed) }
        }
    }

    /**
     * Brings the Rust client in line with the bridge after a pushed token: re-installing the
     * bridge makes the Rust client pull the new token right away, and an ended session removes it.
     * Does nothing if a login, logout or admin auth has moved past [session] since.
     */
    private suspend fun syncAuthCallback(session: Long) {
        authMutex.withLock {
            if (authBridge.currentSession != session) return
            try {
                if (authBridge.hasToken) {
                    ffiClient.setAuthCallback(authBridge)
                } else {
                    ffiClient.setAuthCallback(null)
                    _authState.value = AuthState.Unauthenticated()
                }
            } catch (e: Exception) {
                Log.e("ConvexClientWithAuth", "Error handling token refresh", e)
                _authState.value = AuthState.Unauthenticated()
            }
        }
    }

    private companion object {
        /**
         * The bridge's refresh: a cached login through [authProvider].
         *
         * It MUST hold the client weakly. uniffi's handle map keeps every object the Rust client
         * holds strongly reachable, and the bridge is one of them, so a strong reference here
         * would keep a discarded client, and with it the Rust client, its runtime and its
         * WebSocket, alive until the process exits (the Kotlin side of
         * get-convex/convex-swift#25).
         */
        fun <T> refreshThrough(
            client: WeakReference<ConvexClientWithAuth<T>>,
            authProvider: AuthProvider<T>
        ): suspend (session: Long) -> String? = { session ->
            client.get()?.onIdTokenHandler(session)?.let { onIdToken ->
                authProvider.loginFromCache(onIdToken).getOrNull()?.let(authProvider::extractIdToken)
            }
        }
    }
}

/**
 * The [AuthTokenProvider] that a [ConvexClientWithAuth] hands to the Rust client, adapting the
 * push-based `onIdToken` model to the Rust client's pull-based token callback.
 *
 * A client creates exactly one bridge and keeps it for its whole lifetime. All state is guarded by
 * [lock], because the Rust worker, token pushes and the client's own methods reach it from
 * different threads (the Kotlin side of get-convex/convex-swift#21).
 */
private class AuthTokenProviderBridge(
    private val refresh: suspend (session: Long) -> String?
) : AuthTokenProvider {
    private val lock = Any()

    /** The token handed to the Rust client, or `null` when no session is active. */
    private var token: String? = null

    /**
     * Incremented whenever a session ends, so token callbacks and logins that belong to an
     * earlier session can be told apart from current ones and ignored.
     */
    private var session = 0L

    val currentSession: Long get() = synchronized(lock) { session }

    val hasToken: Boolean get() = synchronized(lock) { token != null }

    /** Starts or continues [session] with [token]. Returns `false` if [session] has ended. */
    fun activate(token: String, session: Long): Boolean = synchronized(lock) {
        if (this.session != session) return false
        this.token = token
        true
    }

    /**
     * Replaces the token of [session] if it is still active. Returns `true` only if the token
     * changed, so a late refresh can never start a session on its own and a token the Rust client
     * already has (for example one it just pulled through [fetchToken]) isn't pushed again.
     */
    fun update(token: String, session: Long): Boolean = synchronized(lock) {
        val current = this.token
        if (this.session != session || current == null || current == token) return false
        this.token = token
        true
    }

    /**
     * Ends the current session, or only [session] when given. Returns `false` if [session] had
     * already ended.
     */
    fun endSession(session: Long? = null): Boolean = synchronized(lock) {
        if (session != null && session != this.session) return false
        this.session += 1
        token = null
        true
    }

    override suspend fun fetchToken(forceRefresh: Boolean): String? {
        val (cached, session) = synchronized(lock) { token to this.session }
        if (cached == null || !forceRefresh) return cached
        // It's not required to treat this as a "force refresh": `refresh` only has to return a
        // valid token, which can be one the AuthProvider cached earlier. A refresh that fails
        // keeps the current token rather than signing the socket out.
        //
        // Nothing but this call's own cancellation may escape: uniffi turns any other exception
        // into a panic inside the Rust client's worker, which stops the client for good, and an
        // Error (such as the NotImplementedError of a provider without loginFromCache) would be
        // uncaught on uniffi's coroutine and crash the process.
        val fresh = try {
            refresh(session)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            Log.e("ConvexClientWithAuth", "Token refresh failed", e)
            null
        } ?: return cached
        return synchronized(lock) {
            // A logout while the refresh was in flight wins over the refreshed token.
            if (this.session != session || token == null) return null
            token = fresh
            fresh
        }
    }
}

/**
 * Authentication states that can be experienced when using an [AuthProvider] with
 * [ConvexClientWithAuth].
 */
sealed class AuthState<T> {
    /**
     * The state that represents an authenticated user.
     *
     * Provides [userInfo] for consumers.
     */
    data class Authenticated<T>(val userInfo: T) : AuthState<T>()

    /**
     * The state that represents an unauthenticated user.
     */
    class Unauthenticated<T> : AuthState<T>()

    /**
     * The state that represents an ongoing authentication attempt.
     */
    class AuthLoading<T> : AuthState<T>()
}

/**
 * An authentication provider, used with [ConvexClientWithAuth].
 *
 * @param T the type of data included upon a successful authentication attempt
 */
interface AuthProvider<T> {
    /**
     * Trigger a login flow, which might launch a new UI/screen.
     *
     * @param context typically an Activity which can be used to launch login UI
     * @param onIdToken a callback to invoke with a fresh JWT ID token. The auth provider should
     *   store this callback and invoke it whenever a new token is available (e.g., on token refresh).
     *   Call with `null` if the session becomes invalid (e.g., token refresh fails).
     * @return a [Result] containing [T] upon successful login
     */
    suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<T>

    /**
     * Trigger a cached, UI-less re-authentication using stored credentials from a previous [login].
     *
     * @param onIdToken a callback to invoke with a fresh JWT ID token. The auth provider should
     *   store this callback and invoke it whenever a new token is available (e.g., on token refresh).
     *   Call with `null` if the session becomes invalid (e.g., token refresh fails).
     * @throws NotImplementedError if this provider doesn't support [loginFromCache]
     * @return a [Result] containing [T] upon successful re-auth
     */
    suspend fun loginFromCache(onIdToken: (String?) -> Unit): Result<T> {
        throw NotImplementedError("$this does not support loginFromCache")
    }

    /**
     * Trigger a logout flow, which might launch a new screen/UI.
     *
     * @param context typically an Activity which can be used to launch logout UI
     * @return a [Result] which indicates whether logout was successful
     */
    suspend fun logout(context: Context): Result<Void?>

    /**
     * Extracts a [JWT ID token](https://openid.net/specs/openid-connect-core-1_0.html#IDToken) from
     * the [T] payload.
     *
     * The [T] payload is returned from a successful [login] attempt.
     */
    fun extractIdToken(authResult: T): String
}
