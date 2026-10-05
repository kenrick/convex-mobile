package dev.convex.android

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

    override suspend fun setAuthCallback(provider: AuthTokenProvider?) {
        this.provider.set(provider)
        provider?.fetchToken(false)
    }

    override suspend fun setAuth(token: String?) {
        provider.set(null)
    }

    override suspend fun setAdminAuth(adminKey: String, actingAsJson: String?) {
        // Like the Rust client, admin auth replaces any installed token callback.
        provider.set(null)
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
