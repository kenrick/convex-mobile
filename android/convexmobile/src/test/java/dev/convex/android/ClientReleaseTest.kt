package dev.convex.android

import android.content.Context
import android.content.ContextWrapper
import dev.convex.android.testing.FakeFfiClient
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isNull

/**
 * Proves a discarded client can be freed while the Rust client still holds the callbacks it was
 * handed, the Kotlin counterpart of get-convex/convex-swift#25.
 *
 * uniffi keeps every Kotlin object the Rust client holds (the WebSocket state subscriber, the
 * auth token provider) in a static handle map until Rust drops it, and Rust drops them only when
 * the Kotlin `MobileConvexClient` is garbage collected and its cleaner frees the Rust client. So if
 * any of those objects references the client, the client, its Rust runtime and its WebSocket stay
 * alive until the process exits.
 */
class ClientReleaseTest {

    @After
    fun releaseRustSide() {
        RustSideFfiClient.handleMap.clear()
    }

    @Test
    fun `a discarded client is freed`() {
        val ffiClient = discardedClient { factory -> ConvexClient("foo://bar", factory) }

        expectThat(collected(ffiClient)).isNull()
    }

    @Test
    fun `a discarded client with auth is freed after a login`() {
        val ffiClient = discardedClient { factory ->
            ConvexClientWithAuth("foo://bar", FakeAuthProvider(), CoroutineScope(SupervisorJob()), factory)
                .also { client -> runBlocking { client.login(ContextWrapper(null)).getOrThrow() } }
        }

        expectThat(collected(ffiClient)).isNull()
    }

    /** Creates a client with [create], then drops it; returns its FFI client, held weakly. */
    private fun discardedClient(
        create: ((String, String, WebSocketStateSubscriber?) -> MobileConvexClientInterface) -> ConvexClient
    ): WeakReference<MobileConvexClientInterface> {
        var ffiClient: WeakReference<MobileConvexClientInterface>? = null
        create { _, _, subscriber -> RustSideFfiClient(subscriber).also { ffiClient = WeakReference(it) } }
        return checkNotNull(ffiClient)
    }

    private fun <T : Any> collected(reference: WeakReference<T>): T? {
        repeat(50) {
            System.gc()
            if (reference.get() == null) return null
            Thread.sleep(20)
        }
        return reference.get()
    }

    /** Holds what it's handed strongly and statically, as uniffi's handle maps do. */
    private class RustSideFfiClient(subscriber: WebSocketStateSubscriber?) :
        MobileConvexClientInterface by FakeFfiClient() {
        init {
            subscriber?.let(handleMap::add)
        }

        override suspend fun setAuthCallback(provider: AuthTokenProvider?) {
            provider?.let(handleMap::add)
        }

        companion object {
            val handleMap = ConcurrentLinkedQueue<Any>()
        }
    }

    private class FakeAuthProvider : AuthProvider<String> {
        private var onIdToken: ((String?) -> Unit)? = null

        override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<String> {
            // Providers keep the callback to push refreshed tokens later.
            this.onIdToken = onIdToken
            return Result.success("token")
        }

        override suspend fun logout(context: Context): Result<Void?> = Result.success(null)

        override fun extractIdToken(authResult: String): String = authResult
    }
}
