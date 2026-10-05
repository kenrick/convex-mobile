package dev.convex.android

import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull

/**
 * Exercises [ConvexClientWithAuth] the way a real app and the Rust client do at the same time:
 * logins and cached logins, token pushes through `onIdToken`, logouts, and the Rust worker pulling
 * tokens through the installed [AuthTokenProvider] (the Kotlin side of
 * get-convex/convex-swift#21 and #26).
 */
class AuthConcurrencyTest {
    private val context: Context = ContextWrapper(null)

    @Test
    fun `concurrent auth transitions are race free`() = runBlocking {
        val core = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.add(e) }
        )
        val client = ConvexClientWithAuth("foo://bar", provider, scope) { _, _, _ -> core }

        withContext(Dispatchers.Default) {
            coroutineScope {
                for (i in 0 until 300) {
                    launch { client.loginFromCache() }
                    launch { provider.pushToken("token-$i") }
                    launch { core.installedProvider?.fetchToken(forceRefresh = i % 3 == 0) }
                    if (i % 25 == 0) {
                        launch { client.logout(context) }
                        launch { provider.pushToken(null) }
                    }
                }
            }
        }
        scope.coroutineContext[Job]?.children?.forEach { it.join() }

        // After the storm settles, a fresh login must leave the core with a working provider.
        client.login(context).getOrThrow()
        scope.coroutineContext[Job]?.children?.forEach { it.join() }
        expectThat(core.installedProvider?.fetchToken(false)).isNotNull()
        expectThat(client.authState.value).isA<AuthState.Authenticated<String>>()
        expectThat(uncaught).isEmpty()
        scope.cancel()
    }

    @Test
    fun `a token pushed after logout doesn't re-authenticate`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> core }

        client.login(context).getOrThrow()
        client.logout(context).getOrThrow()
        expectThat(core.installedProvider).isNull()

        // A provider that still holds the old onIdToken callback pushes a late refresh.
        provider.pushToken("late-token")
        advanceUntilIdle()

        expectThat(core.installedProvider?.fetchToken(false)).isNull()
        expectThat(client.authState.value).isA<AuthState.Unauthenticated<String>>()
    }

    @Test
    fun `a logout during an in-flight login wins`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val provider = GatedAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> core }

        val login = async { client.login(context) }
        provider.loginStarted.await()
        client.logout(context).getOrThrow()
        provider.finishLogin.complete("token")

        expectThat(login.await().exceptionOrNull()).isA<CancellationException>()
        expectThat(client.authState.value).isA<AuthState.Unauthenticated<String>>()
        expectThat(core.installedProvider).isNull()
    }

    @Test
    fun `a session ending in an earlier login doesn't end the current one`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> core }

        client.login(context).getOrThrow()
        val earlierSession = provider.latestCallback
        client.logout(context).getOrThrow()
        val token = client.login(context).getOrThrow()

        earlierSession?.invoke(null)
        earlierSession?.invoke("stale-token")
        advanceUntilIdle()

        expectThat(client.authState.value).isEqualTo(AuthState.Authenticated(token))
        expectThat(core.installedProvider?.fetchToken(false)).isEqualTo(token)
    }

    @Test
    fun `a pushed token replaces the current one`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> core }
        client.login(context).getOrThrow()

        provider.pushToken("refreshed-1")
        provider.pushToken("refreshed-2")
        advanceUntilIdle()

        expectThat(core.installedProvider?.fetchToken(false)).isEqualTo("refreshed-2")
    }

    @Test
    fun `a pushed null ends the session`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> core }
        client.login(context).getOrThrow()

        provider.pushToken(null)
        advanceUntilIdle()

        expectThat(core.installedProvider).isNull()
        expectThat(client.authState.value).isA<AuthState.Unauthenticated<String>>()
    }

    @Test
    fun `a failing refresh keeps the current token`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> core }
        val token = client.login(context).getOrThrow()

        provider.refreshFailure = IllegalStateException("offline")
        expectThat(core.installedProvider?.fetchToken(true)).isEqualTo(token)

        // Errors too: one escaping uniffi's callback coroutine would crash the process.
        provider.refreshFailure = NotImplementedError("no cached login")
        expectThat(core.installedProvider?.fetchToken(true)).isEqualTo(token)
    }

    @Test
    fun `a provider without loginFromCache keeps the login token on refresh`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val client = ConvexClientWithAuth("foo://bar", LoginOnlyAuthProvider(), this) { _, _, _ -> core }
        client.login(context).getOrThrow()

        expectThat(core.installedProvider?.fetchToken(true)).isEqualTo("login-token")
    }

    @Test
    fun `a logout during a refresh wins over the refreshed token`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val provider = GatedAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> core }
        val login = async { client.login(context) }
        provider.loginStarted.await()
        provider.finishLogin.complete("token")
        login.await().getOrThrow()
        val bridge = core.installedProvider

        provider.gateRefresh()
        val refresh = async { bridge?.fetchToken(true) }
        provider.refreshStarted.await()
        client.logout(context).getOrThrow()
        provider.finishRefresh.complete("refreshed")

        expectThat(refresh.await()).isNull()
        expectThat(bridge?.fetchToken(false)).isNull()
    }

    @Test
    fun `a login whose provider throws ends unauthenticated`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val client = ConvexClientWithAuth("foo://bar", ThrowingAuthProvider(), this) { _, _, _ -> core }

        val result = client.login(context)

        expectThat(result.exceptionOrNull()).isA<IllegalStateException>()
        expectThat(client.authState.value).isA<AuthState.Unauthenticated<String>>()
    }

    @Test
    fun `loginFromCache without provider support throws and ends unauthenticated`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val client = ConvexClientWithAuth("foo://bar", LoginOnlyAuthProvider(), this) { _, _, _ -> core }

        val error = runCatching { client.loginFromCache() }.exceptionOrNull()

        expectThat(error).isA<NotImplementedError>()
        expectThat(client.authState.value).isA<AuthState.Unauthenticated<String>>()
    }

    @Test
    fun `a new login after logout uses a fresh token`() = runTest {
        val core = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> core }

        val first = client.login(context).getOrThrow()
        client.logout(context).getOrThrow()
        val second = client.login(context).getOrThrow()

        expectThat(second).isNotEqualTo(first)
        expectThat(core.installedProvider?.fetchToken(false)).isEqualTo(second)
    }
}

/** Logs in only when released, and optionally holds token refreshes the same way. */
private class GatedAuthProvider : AuthProvider<String> {
    val loginStarted = CompletableDeferred<Unit>()
    val finishLogin = CompletableDeferred<String>()
    val refreshStarted = CompletableDeferred<Unit>()
    val finishRefresh = CompletableDeferred<String>()

    @Volatile
    private var refreshGated = false

    fun gateRefresh() {
        refreshGated = true
    }

    override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<String> {
        loginStarted.complete(Unit)
        return Result.success(finishLogin.await())
    }

    override suspend fun loginFromCache(onIdToken: (String?) -> Unit): Result<String> {
        if (!refreshGated) return Result.success(finishLogin.await())
        refreshStarted.complete(Unit)
        return Result.success(finishRefresh.await())
    }

    override suspend fun logout(context: Context): Result<Void?> = Result.success(null)

    override fun extractIdToken(authResult: String): String = authResult
}

/** Throws from login instead of returning a failed [Result]. */
private class ThrowingAuthProvider : AuthProvider<String> {
    override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<String> =
        throw IllegalStateException("browser unavailable")

    override suspend fun logout(context: Context): Result<Void?> = Result.success(null)

    override fun extractIdToken(authResult: String): String = authResult
}

/** Relies on the default [AuthProvider.loginFromCache], which throws [NotImplementedError]. */
private class LoginOnlyAuthProvider : AuthProvider<String> {
    override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<String> =
        Result.success("login-token")

    override suspend fun logout(context: Context): Result<Void?> = Result.success(null)

    override fun extractIdToken(authResult: String): String = authResult
}
