package dev.convex.android

import dev.convex.android.testing.FakeFfiClient
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue

/**
 * How subscription results reach a [kotlinx.coroutines.flow.Flow], including results the Rust
 * client delivers while or after the collector is cancelled (get-convex/convex-mobile#24).
 */
class SubscriptionDeliveryTest {
    private val ffiClient = FakeFfiClient()
    private val client = ConvexClient("foo://bar") { _, _, _ -> ffiClient }

    @Test
    fun `a result that doesn't decode arrives as an InternalError and the subscription stays open`() = runTest {
        val results = mutableListOf<Result<Foo>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>("foo").collect { results.add(it) }
        }

        ffiClient.sendSubscriptionData("foo", mapOf(), """{"unexpected":1}""")
        ffiClient.sendSubscriptionData("foo", mapOf(), Json.encodeToString(Foo(bar = "baz")))

        expectThat(results).hasSize(2)
        expectThat(results[0].exceptionOrNull()).isA<InternalError>()
        expectThat(results[1].getOrNull()).isEqualTo(Foo(bar = "baz"))
        expectThat(ffiClient.hasSubscriptionFor("foo", mapOf())).isTrue()
    }

    @Test
    fun `results delivered after the collector was cancelled are dropped without throwing`() = runTest {
        val results = mutableListOf<Result<Foo>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>("foo").collect { results.add(it) }
        }
        val subscriber = ffiClient.subscriptions.values.single()

        job.cancel()
        expectThat(ffiClient.hasSubscriptionFor("foo", mapOf())).isFalse()

        // The Rust client can still be delivering when the subscription is cancelled.
        subscriber.onError("Server Error", """{"status":401,"message":"UNAUTHENTICATED"}""")
        subscriber.onError("Server Error", null)
        subscriber.onUpdate(Json.encodeToString(Foo(bar = "late")))
        subscriber.onUpdate("not json")

        expectThat(results).isEmpty()
    }

    @Test
    fun `errors racing cancelling collectors never escape the callback`() = runBlocking<Unit> {
        val ffiClient = ThreadSafeFakeFfiClient()
        val client = ConvexClient("foo://bar") { _, _, _ -> ffiClient }
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught.add(e) }
        )

        repeat(200) { i ->
            val name = "query-$i"
            val job = scope.launch {
                client.subscribe<Foo>(name).collect { it.exceptionOrNull() }
            }
            withTimeout(5_000) {
                while (ffiClient.subscribers[name] == null) kotlinx.coroutines.yield()
            }
            val subscriber = ffiClient.subscribers.getValue(name)
            // Deliver from another thread, as the Rust client does, while the collector cancels.
            val delivery = Thread {
                repeat(50) {
                    subscriber.onError("Server Error", """{"status":401,"message":"UNAUTHENTICATED"}""")
                    subscriber.onUpdate("not json")
                }
            }
            delivery.start()
            job.cancel()
            delivery.join()
            job.join()
        }
        scope.cancel()

        expectThat(uncaught).isEmpty()
        expectThat(ffiClient.subscribers).isEmpty()
    }

    /**
     * The crash in get-convex/convex-mobile#24 has a `ConvexError` created in
     * `QuerySubscriber.onError` (so its stack shows the uniffi/JNA callback frames) failing a
     * `StandaloneCoroutine{Cancelling}` on `Dispatchers.IO`. That is the signature of a collector
     * that rethrows a delivered `Result.failure` with no handler: a job that fails is
     * "Cancelling" while it reports the failure, whether or not anything cancelled it. The
     * client never throws a delivered error itself.
     */
    @Test
    fun `the #24 crash signature is a collector rethrowing a delivered error`() = runBlocking<Unit> {
        val ffiClient = ThreadSafeFakeFfiClient()
        val client = ConvexClient("foo://bar") { _, _, _ -> ffiClient }
        val crash = CompletableDeferred<Pair<Throwable, String>>()
        val scope = CoroutineScope(
            Dispatchers.IO + CoroutineExceptionHandler { context, e -> crash.complete(e to context[Job].toString()) }
        )

        scope.launch {
            client.subscribe<Foo>("foo").collect { it.getOrThrow() }
        }
        withTimeout(5_000) {
            while (ffiClient.subscribers["foo"] == null) kotlinx.coroutines.yield()
        }
        val subscriber = ffiClient.subscribers.getValue("foo")
        Thread {
            subscriber.onError("Server Error", """{"status":401,"message":"UNAUTHENTICATED"}""")
        }.apply { start() }.join()

        val (error, job) = withTimeout(5_000) { crash.await() }
        expectThat(error).isA<ConvexError>()
        expectThat(error.stackTrace.first { it.className.startsWith("dev.convex") }.toString())
            .contains("SubscriptionAdapter.onError")
        expectThat(job).contains("StandaloneCoroutine{Cancelling}")
    }

    @Test
    fun `a collector that falls behind gets the newest result`() = runTest {
        val results = mutableListOf<Foo>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            client.subscribe<Foo>("foo").collect { results.add(it.getOrThrow()) }
        }
        runCurrent()

        // More results than a default channel buffer (64) holds, before the collector runs.
        repeat(100) { i ->
            ffiClient.sendSubscriptionData("foo", mapOf(), Json.encodeToString(Foo(bar = "v$i")))
        }
        runCurrent()

        expectThat(results.last()).isEqualTo(Foo(bar = "v99"))
    }

    @Test
    fun `a subscription that can't start fails the Flow with a client error`() = runTest {
        val failing = object : MobileConvexClientInterface by FakeFfiClient() {
            override suspend fun subscribe(
                name: String,
                args: Map<String, String>,
                subscriber: QuerySubscriber
            ): SubscriptionHandle = throw ClientException.InternalException("no connection")
        }
        val client = ConvexClient("foo://bar") { _, _, _ -> failing }

        val error = runCatching { client.subscribe<Foo>("foo").collect {} }.exceptionOrNull()

        expectThat(error).isA<InternalError>().get { message }.isEqualTo("no connection")
    }
}
