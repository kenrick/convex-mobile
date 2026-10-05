# harby's convex-mobile patches

Branch `harby/0.8.1-patched` of `kenrick/convex-mobile`, from `get-convex/convex-mobile` `720a79a` (the commit that points the `ios` submodule at convex-swift 0.8.1; its `rust/` tree built 0.8.1's XCFramework). The iOS side, `kenrick/convex-swift` on the branch of the same name, documents the Rust patches below in its own `HARBY-PATCHES.md` and builds its XCFramework from this branch.

harby's Android app consumes the AAR built here from a Maven repository committed in this branch, `maven/`, by commit-pinned URL. The library keeps upstream's coordinates and gets a harby version:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven { url = uri("https://raw.githubusercontent.com/kenrick/convex-mobile/<commit>/maven") }
            }
            filter { includeModule("dev.convex", "android-convexmobile") }
        }
        google()
        mavenCentral()
    }
}

// build.gradle.kts
dependencies {
    implementation("dev.convex:android-convexmobile:0.8.1-harby.1")
}
```

`<commit>` is the tip of `harby/0.8.1-patched` at the time of pinning. `exclusiveContent` keeps Gradle from asking Maven Central for the fork's version and the fork's repository for anything else. No `@aar` or `isTransitive` is needed: the Gradle module metadata selects the AAR and brings its dependencies.

## Rust patches (also in the iOS XCFramework)

| Commit | Patch | Upstream |
|---|---|---|
| `bc99879` | Drop uniffi's `cli` feature from the library (no `uniffi_bindgen` / `cargo_metadata` in the shipped library). The `[[bin]]` declares `required-features = ["uniffi/cli"]`, and the Android Gradle tasks pass `--features=uniffi/cli`. | [PR #23](https://github.com/get-convex/convex-mobile/pull/23) |
| `f290429` | convex-rs 0.10.3 → 0.10.4, which fixes a query-subscription memory leak. | [convex-rs#15](https://github.com/get-convex/convex-rs/issues/15) |
| `3e201e3` | Export `set_admin_auth(admin_key, acting_as_json)`. The identity JSON is parsed by convex_sync_types' own wire conversion. | none |
| `ab03b31` | `build-ios.sh`: minimum OS pinned for Rust and C code, build paths remapped, `--locked`, configurable `IOS_DIR`. iOS only. | related: convex-swift [#9](https://github.com/get-convex/convex-swift/issues/9), [#10](https://github.com/get-convex/convex-swift/issues/10) |

## Android patches

| Commit | Patch | Upstream |
|---|---|---|
| `5d4778d` | Build with current tools, only as far as consumers on Kotlin 2.4 / kotlinx.serialization 1.11 / AGP 9 / compileSdk 36-37 need. See below. The library hadn't compiled since `3e201e3` (`FakeFfiClient` lacked `setAdminAuth`). | none |
| `199e9f0` | Subscription results are values, never thrown, and conflated. See below. | [#24](https://github.com/get-convex/convex-mobile/issues/24) |
| `91630b0` | Fixes the auth bridge's races, the refresh failures that crashed the process or killed the client, and the leak of discarded clients. See below. | Kotlin side of convex-swift [#21](https://github.com/get-convex/convex-swift/issues/21), [#25](https://github.com/get-convex/convex-swift/pull/25), [#26](https://github.com/get-convex/convex-swift/issues/26); [#21](https://github.com/get-convex/convex-mobile/issues/21) |
| `97051b3` | `ConvexClient.setAdminAuth(adminKey, identity)` plus `UserIdentity`, mirroring convex-swift's `setAdminAuth(adminKey:identity:)`. | none (convex-rs has `set_admin_auth`) |
| `834bd02` | The sources jar carries the generated bindings; `androidx.test:runner` for the instrumented tests. | none |
| `e4d0923` | Build the native libraries before packaging them: a build from a clean checkout made an AAR without any `libconvexmobile.so` (270 KB), because nothing ordered `cargoBuild` before `merge*JniLibFolders`. | none |
| `7ceb2f2` | Ship R8 keep rules (`consumer-rules.pro`, as the AAR's `proguard.txt`) for JNA and the bindings. See below. | none |

### Build (`5d4778d`)

- AGP 8.13.2, Gradle 8.14.5 (wrapper with checksum), Kotlin 2.2.21, rust-android-gradle 0.10.0 (AGP 8 / Gradle 9 support; it links with `-z max-page-size=16384` itself for NDK ≤ 27, so the manual link-arg override is gone). compileSdk 36; minSdk stays 26; JVM target stays 1.8. The library's own build doesn't need AGP 9: rust-android-gradle uses the variant API AGP 9 removed.
- Published dependencies: appcompat, material and core-ktx are gone (unused). `kotlinx-coroutines-core` 1.10.2 and `kotlinx-serialization-json` 1.9.0 are `api`, because `Flow` and serialization types are in the API and the inline functions compile into callers. JNA 5.14.0 → 5.17.0, also `api` (the generated bindings expose `Pointer`): 5.14.0's x86_64 and armeabi-v7a `libjnidispatch.so` are 4 KB aligned, 5.17.0's are 16 KB aligned on every ABI. All are lower bounds that a newer consumer resolves upward.
- A fixed version (`0.8.1-harby.1`) instead of one derived from git tags; a single release variant with a sources jar; signing only when a signatory is configured; a `forkRepo` repository at `../maven`.
- cargo runs with `--locked` and remaps build paths out of the binary, as `build-ios.sh` does.

### Subscriptions (`199e9f0`)

#24 reports a `ConvexError` from `QuerySubscriber.onError` killing the process while the collector cancels. The client never throws a delivered error: `onError` only sends `Result.failure` to the Flow. In the reported trace, the error's stack is where `onError` created it, and a failing `StandaloneCoroutine{Cancelling}` on `Dispatchers.IO` is how any launched job looks while it reports its own failure. That is a collector rethrowing the `Result` (for example `getOrThrow()`) without a handler; `SubscriptionDeliveryTest` reproduces the exact signature. The delivery side is hardened anyway:

- The `QuerySubscriber` is a library class instead of code inlined into each caller, and it never throws: uniffi turns an exception from a callback into a Rust panic that silently ends the subscription.
- A result that doesn't decode arrives as `Result.failure(InternalError)` and the subscription stays open. Before, it cancelled the producer with a `CancellationException`, which quietly cancelled the collector too.
- Results that arrive after the Flow closed are dropped before decoding. The Rust client keeps delivering until it sees the cancel.
- A subscription that can't start fails the Flow with `InternalError` instead of the raw uniffi `ClientException`.
- Results are conflated. `trySend` into callbackFlow's default 64-slot buffer dropped the newest result once a slow collector fell 64 behind, leaving it on stale data for good.
- Arguments may be kotlinx `JsonElement`s, sent as they are. Their numbers are JSON numbers, i.e. float64, which is what `v.number()` accepts. Kotlin `Int`/`Long` arguments are still `$integer` (int64), as upstream sends them.

convex-swift's unaligned `$integer` / `$float` load (its `42f1bbe`) has no Kotlin counterpart: the decoders read through a `ByteBuffer`, which has no alignment requirement. `JsonHelpersTest` pins it.

### Auth (`91630b0`)

- One `AuthTokenProviderBridge` per client, kept for its lifetime, with its state behind a lock. Before, each login built a new bridge and swapped it into a plain `var` that token pushes, logins and logouts raced on.
- A session counter makes token pushes, refreshes and logins from an ended session inert.
  - A `logout()` that completes during `login()` wins: the login returns a `CancellationException` failure and leaves the client logged out. Before, it installed its token after the logout.
  - A late token, or `null`, pushed through an earlier session's `onIdToken` no longer re-authenticates, or logs out, the current session.
  - Unchanged tokens aren't re-installed.
  - Pushed tokens reach the bridge in the order the provider pushes them; only re-installing the callback is asynchronous.
- A failing forced refresh keeps the current token. Before, it handed the socket `null`, silently signing it out while `authState` said `Authenticated`.
- `fetchToken` never throws, except for its own cancellation. uniffi turns any other exception into a panic in the convex-rs worker, which stops the client for good. An `Error`, such as the `NotImplementedError` of a provider without `loginFromCache`, was uncaught on uniffi's `GlobalScope` coroutine and crashed the process on the first reconnect.
- `login()` / `loginFromCache()` no longer leave `authState` at `AuthLoading` when the provider throws or the call is cancelled. Exceptions become the failed `Result`; errors, such as the documented `NotImplementedError`, still propagate.
- Leaks (Kotlin side of convex-swift #25). uniffi's static handle maps keep everything the Rust client holds strongly reachable. Both the bridge (an inner class) and the WebSocket state subscriber (an anonymous object) referenced the client, so a discarded client kept its Rust runtime and WebSocket until process exit. Both now hold only what they need, and hold the client weakly. Apps should still keep one client per process.

Use-after-free (convex-swift #26) has no Kotlin counterpart: uniffi's Kotlin handle maps are garbage-collected objects, not manually retained pointers.

### Admin auth (`97051b3`)

`ConvexClient.setAdminAuth(adminKey, identity)` is for local development only: an admin key has full access to its deployment. `UserIdentity` mirrors `UserIdentityAttributes`: the protocol's camelCase keys, with unset fields left out. The `identity` of harby's `GET /auth/dev-token` decodes straight into it with a default `Json`. An identity with neither `tokenIdentifier` nor both `issuer` and `subject` is rejected with `InternalError`. On `ConvexClientWithAuth`, `setAdminAuth` first ends any login session, so a late token refresh can't replace the admin auth. `authState` doesn't track admin auth, and `logout()` clears it.

### R8 rules (`7ceb2f2`)

Neither JNA's AAR nor upstream's carries consumer rules, so an app that minifies fails on its first client: JNA's native init looks up `com.sun.jna.Pointer.peer` by name and throws `UnsatisfiedLinkError: Can't obtain peer field ID for class com.sun.jna.Pointer`. The AAR now keeps JNA's classes and the public members of its `Structure`/`Callback` subclasses, ignores JNA's AWT references, and keeps `dev.convex.android`. Checked with a minified AGP 9 release build on an API 36 emulator, which fails as above without the rules and runs the dev-auth flow with them.

## Tests

- `cd android && ./gradlew :convexmobile:testDebugUnitTest` runs 40 JVM tests: upstream's 13, plus `SubscriptionDeliveryTest`, `AuthConcurrencyTest`, `ClientReleaseTest`, `AdminAuthTest` and `JsonHelpersTest`. 16 of the 27 new tests fail on the client before these patches (given a pass-through `setAdminAuth` so they compile). `AuthConcurrencyTest`'s storm test passes on both (without a race detector it finds no race), as does the delivery race test.
- Instrumented: `./gradlew :convexmobile:assembleDebugAndroidTest`, then on a device `am instrument -w -e class dev.convex.android.ConvexClientWithAuthTest dev.convex.android.test/androidx.test.runner.AndroidJUnitRunner`. Upstream's 11 tests pass. `ConvexClientInstrumentationTest` targets upstream's shared cloud test deployment, so it is skipped, like convex-swift's `IntegrationTests`.
- `cd rust && cargo test --locked --lib` runs 7 tests.
- End to end, from a scratch app on Kotlin 2.4.20 / AGP 9.4.1 / kotlinx.serialization 1.11.0 / compileSdk 36 / minSdk 28 that resolves the published AAR, on an API 36 emulator against a local anonymous Convex and harby's web server (`adb reverse`): an unauthenticated `workspaces:mySpaces` subscription returns `[]`; `GET /auth/dev-token` then `setAdminAuth` makes the same live subscription return the dev user's space; `logout()` takes it back to `[]`. A query with invalid arguments arrives as a `ServerError` value. The flow passes in a debug build and in a minified release build.

## Rebuilding and publishing

Toolchain: rustc 1.95.0 with the `aarch64-linux-android`, `armv7-linux-androideabi` and `x86_64-linux-android` targets; NDK 27.3.13750724; cmake on `PATH` (the SDK's `cmake/3.22.1/bin` works; aws-lc-sys needs it); python3; JDK 17. `android/local.properties` has `sdk.dir`.

```sh
cd android
./gradlew :convexmobile:testDebugUnitTest
rm -rf ../maven && ./gradlew :convexmobile:publishReleasePublicationToForkRepoRepository
```

That writes `maven/dev/convex/android-convexmobile/<version>/`: the AAR (arm64-v8a, armeabi-v7a, x86_64), POM, Gradle module metadata, sources jar, and their md5/sha1/sha256/sha512 checksums, plus `maven-metadata.xml`. Bump `libraryVersion` in `android/convexmobile/build.gradle` for every published change, so that one version never has two contents. Commit `maven/`, push, then re-pin the app's URL.

A clean build from a fresh clone at a different path reproduces the committed AAR, POM, module and sources jar byte for byte; only `maven-metadata.xml` differs, by its `lastUpdated` timestamp.

SHA-256 of `0.8.1-harby.1`, built from `7ceb2f2` (each file also has `.sha256` and the other checksums next to it):

```
87d0a5da1565e16bf561a54a3052b579f7e11e19aa355f56179fc5fa8f17eb00  android-convexmobile-0.8.1-harby.1.aar
c4e4244018c928762e7f20ed48e7d30d6f5d9f98fcdae16c7758718aad5cbe15  android-convexmobile-0.8.1-harby.1.pom
0257bb0f520de80a90c0b550229b341d6313c30be7511026f98c3b5f2a318f6b  android-convexmobile-0.8.1-harby.1.module
ed3b791bd517b1a5449c5b22693e48aeb1895feaae653bd1d36b253797539a73  android-convexmobile-0.8.1-harby.1-sources.jar
```

## Size

| | AAR | arm64-v8a `.so` | armeabi-v7a `.so` | x86_64 `.so` |
|---|---|---|---|---|
| 0.8.1-harby.1 | 7.65 MB | 5.76 MB (2.62 MB gzip -9) | 3.55 MB (1.97 MB) | 6.91 MB (2.77 MB) |
| upstream 0.8.0 | 7.89 MB | 5.96 MB | 3.66 MB | 7.20 MB |

The `.so` files in the AAR are stripped by AGP (cargo's release outputs are 7.97, 5.16 and 8.47 MB) and 16 KB aligned (`LOAD` segments aligned to `0x4000`; `zipalign -c -P 16` passes on an APK). JNA adds about 0.12-0.17 MB of `libjnidispatch.so` per ABI. JNA's AAR also ships armeabi, mips, mips64 and x86 builds, so apps should set `abiFilters`.

## Rebasing onto a future upstream release

1. Rebase onto the convex-mobile commit of the new release, on a branch named after it (for example `harby/0.9.0-patched`). Drop patches upstream has merged. `f290429` is redundant once upstream depends on convex ≥ 0.10.4.
2. Resolve `ConvexClient.kt` by keeping the single-bridge design and the value-only subscription delivery, unless upstream fixed them itself.
3. Run the tests above, then republish with a new version and re-pin both apps (the XCFramework is rebuilt from the same commit; see convex-swift's `HARBY-PATCHES.md`).
