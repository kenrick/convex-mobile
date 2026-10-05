package dev.convex.android

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Stores the provider handed to `setAuthCallback` and pulls a token from it right away, like the
 * Rust client does; safe to call from any thread.
 */
internal class ThreadSafeFakeFfiClient : MobileConvexClientInterface {
    private val provider = AtomicReference<AuthTokenProvider?>()
    val subscribers = ConcurrentHashMap<String, QuerySubscriber>()

    val installedProvider: AuthTokenProvider? get() = provider.get()

    /** Whether admin auth is in effect: each kind of auth replaces the others. */
    @Volatile
    var adminAuthActive = false
        private set

    override suspend fun setAuthCallback(provider: AuthTokenProvider?) {
        adminAuthActive = false
        this.provider.set(provider)
        provider?.fetchToken(false)
    }

    override suspend fun setAuth(token: String?) {
        adminAuthActive = false
        provider.set(null)
    }

    override suspend fun setAdminAuth(adminKey: String, actingAsJson: String?) {
        provider.set(null)
        adminAuthActive = true
    }

    override suspend fun query(name: String, args: Map<String, String>): String = "null"

    override suspend fun mutation(name: String, args: Map<String, String>): String = "null"

    override suspend fun action(name: String, args: Map<String, String>): String = "null"

    override suspend fun subscribe(
        name: String,
        args: Map<String, String>,
        subscriber: QuerySubscriber
    ): SubscriptionHandle {
        subscribers[name] = subscriber
        return object : SubscriptionHandle(NoPointer) {
            override fun cancel() {
                subscribers.remove(name, subscriber)
            }
        }
    }
}

/**
 * An [AuthProvider] that, like WorkOS or Clerk wrappers, keeps the latest `onIdToken` callback and
 * pushes refreshed tokens through it.
 */
internal class ThreadSafeFakeAuthProvider : AuthProvider<String> {
    private val lock = Any()
    private var onIdToken: ((String?) -> Unit)? = null
    private var counter = 0

    /** When set, cached logins (and so token refreshes) fail with it. */
    @Volatile
    var refreshFailure: Throwable? = null

    val latestCallback: ((String?) -> Unit)? get() = synchronized(lock) { onIdToken }

    override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<String> =
        Result.success(issue(onIdToken))

    override suspend fun loginFromCache(onIdToken: (String?) -> Unit): Result<String> {
        refreshFailure?.let { return Result.failure(it) }
        return Result.success(issue(onIdToken))
    }

    override suspend fun logout(context: Context): Result<Void?> = Result.success(null)

    override fun extractIdToken(authResult: String): String = authResult

    fun pushToken(token: String?) {
        latestCallback?.invoke(token)
    }

    private fun issue(callback: (String?) -> Unit): String {
        val token = synchronized(lock) {
            onIdToken = callback
            counter += 1
            "login-$counter"
        }
        callback(token)
        return token
    }
}
