package dev.convex.android

import dev.convex.android.testing.FakeFfiClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class JsonHelpersTest {

    /**
     * convex-swift decoded `$integer` with an aligned load, which traps on unaligned buffers. These
     * decoders read the little-endian bytes through a `ByteBuffer`, which has no alignment
     * requirement.
     */
    @Test
    fun `$integer and $float values decode`() {
        val decoded = jsonApi.decodeFromString<Numbers>(
            """
            {"long":{"${'$'}integer":"KgAAAAAAAAA="},"negative":{"${'$'}integer":"//////////8="},
             "min":{"${'$'}integer":"AAAAAAAAAIA="},"int":{"${'$'}integer":"BwAAAAAAAAA="},
             "nan":{"${'$'}float":"AAAAAAAA+H8="},"plain":1.5}
            """
        )

        expectThat(decoded).isEqualTo(
            Numbers(long = 42, negative = -1, min = Long.MIN_VALUE, int = 7, nan = Double.NaN, plain = 1.5)
        )
    }

    @Test
    fun `JSON elements are sent as they are`() = runTest {
        val ffiClient = FakeFfiClient()
        val client = ConvexClient("foo://bar") { _, _, _ -> ffiClient }

        client.mutation(
            "foo",
            mapOf(
                "object" to buildJsonObject {
                    put("numItems", 25)
                    put("cursor", null as String?)
                },
                "array" to JsonArray(listOf(JsonPrimitive(1.5), JsonPrimitive("a"))),
                "number" to JsonPrimitive(3),
                "nested" to mapOf("count" to JsonPrimitive(2))
            )
        )

        val args = ffiClient.mutations.getValue("foo")
        // JSON numbers are float64 in Convex, the type `v.number()` accepts.
        expectThat(args["object"]).isEqualTo("""{"numItems":25,"cursor":null}""")
        expectThat(args["array"]).isEqualTo("""[1.5,"a"]""")
        expectThat(args["number"]).isEqualTo("3")
        expectThat(args["nested"]).isEqualTo("""{"count":2}""")
    }

    @Serializable
    private data class Numbers(
        val long: Int64,
        val negative: Int64,
        val min: Int64,
        val int: Int32,
        val nan: Float64,
        val plain: Float64
    )
}
