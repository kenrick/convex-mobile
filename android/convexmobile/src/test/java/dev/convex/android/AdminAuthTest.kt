package dev.convex.android

import android.content.ContextWrapper
import dev.convex.android.testing.FakeFfiClient
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue

class AdminAuthTest {

    @Test
    fun `setAdminAuth sends the key and the identity's JSON`() = runTest {
        val ffiClient = FakeFfiClient()
        val client = ConvexClient("foo://bar") { _, _, _ -> ffiClient }

        client.setAdminAuth(
            "admin-key",
            UserIdentity(
                tokenIdentifier = "harby-local-dev|local-dev:dev@example.com",
                issuer = "harby-local-dev",
                subject = "local-dev:dev@example.com",
                name = "Dev",
                email = "dev@example.com",
                emailVerified = true
            )
        )

        val adminAuth = ffiClient.receivedAdminAuth
        expectThat(adminAuth?.adminKey).isEqualTo("admin-key")
        val identity = Json.parseToJsonElement(adminAuth?.actingAsJson ?: "null").jsonObject
        // The protocol's camelCase keys, without unset fields.
        expectThat(identity.keys)
            .isEqualTo(setOf("tokenIdentifier", "issuer", "subject", "name", "email", "emailVerified"))
        expectThat(identity["subject"]).isEqualTo(JsonPrimitive("local-dev:dev@example.com"))
        expectThat(identity["emailVerified"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun `setAdminAuth without an identity`() = runTest {
        val ffiClient = FakeFfiClient()
        val client = ConvexClient("foo://bar") { _, _, _ -> ffiClient }

        client.setAdminAuth("admin-key")

        expectThat(ffiClient.receivedAdminAuth?.adminKey).isEqualTo("admin-key")
        expectThat(ffiClient.receivedAdminAuth?.actingAsJson).isNull()
    }

    @Test
    fun `an identity the client rejects is an InternalError`() = runTest {
        val rejecting = object : MobileConvexClientInterface by FakeFfiClient() {
            override suspend fun setAdminAuth(adminKey: String, actingAsJson: String?) =
                throw ClientException.InternalException("Either tokenIdentifier or issuer and subject is required")
        }
        val client = ConvexClient("foo://bar") { _, _, _ -> rejecting }

        val error = runCatching { client.setAdminAuth("admin-key", UserIdentity(name = "No subject")) }
            .exceptionOrNull()

        expectThat(error).isA<InternalError>()
    }

    @Test
    fun `the dev-token response decodes straight into UserIdentity`() {
        // The body of harby's GET /auth/dev-token, decoded with a default Json.
        val body = """
            {"adminToken":"admin-key","user":{"email":"dev@example.com","name":"Dev"},
             "identity":{"subject":"local-dev:dev@example.com","issuer":"harby-local-dev",
               "tokenIdentifier":"harby-local-dev|local-dev:dev@example.com",
               "email":"dev@example.com","name":"Dev","emailVerified":true}}
        """

        val credentials = Json.decodeFromString<DevAuthCredentials>(body)

        expectThat(credentials.identity).isEqualTo(
            UserIdentity(
                tokenIdentifier = "harby-local-dev|local-dev:dev@example.com",
                issuer = "harby-local-dev",
                subject = "local-dev:dev@example.com",
                name = "Dev",
                email = "dev@example.com",
                emailVerified = true
            )
        )
    }

    @Test
    fun `admin auth ends the provider session`() = runTest {
        val ffiClient = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> ffiClient }
        client.login(ContextWrapper(null)).getOrThrow()
        expectThat(ffiClient.installedProvider).isNotNull()

        client.setAdminAuth("admin-key", UserIdentity(tokenIdentifier = "t"))
        expectThat(ffiClient.installedProvider).isNull()

        // A refresh from the earlier session must not replace the admin auth.
        provider.pushToken("late-token")
        advanceUntilIdle()
        expectThat(ffiClient.installedProvider).isNull()
    }

    @Test
    fun `a session the provider ended just before admin auth doesn't clear it`() = runTest {
        val ffiClient = ThreadSafeFakeFfiClient()
        val provider = ThreadSafeFakeAuthProvider()
        val client = ConvexClientWithAuth("foo://bar", provider, this) { _, _, _ -> ffiClient }
        client.login(ContextWrapper(null)).getOrThrow()

        // The provider ends its session; removing the callback is scheduled, not yet run.
        provider.pushToken(null)
        client.setAdminAuth("admin-key", UserIdentity(tokenIdentifier = "t"))
        advanceUntilIdle()

        expectThat(ffiClient.adminAuthActive).isTrue()
    }

    @Serializable
    private data class DevAuthCredentials(val adminToken: String, val user: User, val identity: UserIdentity) {
        @Serializable
        data class User(val email: String, val name: String? = null)
    }
}
