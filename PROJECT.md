# Project: NetEase UNM Architecture & Refactoring

## Architecture
Decoupled multi-tier architecture isolating network hooks, protocol codecs, domain logic, and sound source engine infrastructure:

```
[Host App / LSPosed]
         │
         ▼
[Hook Layer: pochita.hook]
  ├── NetEaseHook (Facade & Entry Point)
  ├── CronetInterceptor (CronetHttpURLConnection stream & length wrapping)
  ├── OkHttpInterceptor (RealCall response interception, stream bug fix)
  └── HookUrlMatcher (URL detection for /eapi/, /xeapi/, /player/url)
         │
         ├───► [Codec Layer: pochita.codec]
         │       ├── ProtocolCodec (Unified protocol encode/decode orchestrator)
         │       ├── GzipCodec (Deflate/Inflate stream handling)
         │       └── EApiCrypto (AES-128-ECB crypto, PKCS5 padding, MD5)
         │
         ├───► [Domain / Transformer Layer: pochita.domain.transformer]
         │       └── SongRightsTransformer (Pure recursive JSON unblocking: st, fee, cp, pl, dl, trialMode)
         │
         └───► [Resolver Layer: pochita.resolver]
                 └── PlayerUrlResolver (Song metadata extraction, trial replacement, source matching)
                         │
                         ▼
[Source & Engine Layer: pochita.source & pochita.rule]
  ├── RuleManager (Rule update, dry-run validation, atomic file swap, rollback)
  ├── AudioValidator (Unified teaser/blacklist audio ID validation)
  ├── QuickJsEngine (Lifecycle management, bytecode precompilation, single-threaded execution)
  └── NativeSources (Bodian, Kuwo, Migu, Kugou sound sources)
         │
         ▼
[Common Infrastructure Layer: pochita.common]
  ├── UnmHttpClient (Unified OkHttpClient wrapper: connection pool, timeouts, auto-redirects)
  ├── UnmLruCache (Pure Kotlin thread-safe concurrent LRU cache with atomic compute)
  └── UnmLogger (Unified logging facade: XposedModule.log in host, stdout in JVM tests)
```

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | UnmLogger Facade | Pluggable logger adapting to Xposed host log and JVM test stdout, with silent error suppression | M1 | ORIGINAL_REQUEST §R2 |
| 2 | UnmLruCache | Pure Kotlin thread-safe LRU cache with atomic compute semantics replacing Collections.synchronizedMap | M1 | ORIGINAL_REQUEST §R2 |
| 3 | UnmHttpClient | Unified lightweight HTTP client wrapping OkHttp with connection reuse, timeouts, and User-Agent | M1 | ORIGINAL_REQUEST §R2 |
| 4 | Codec Layer & Gzip/EApi | Decoupled GzipCodec, EApiCrypto, and ProtocolCodec with Content-Length / Header adjustments | M1 | ORIGINAL_REQUEST §R1 |
| 5 | SongRightsTransformer | Pure recursive JSON tree unblocker for /eapi/ and /xeapi/ responses (st=0, fee=0, cp=1, pl=320000, trial clearing) | M2 | ORIGINAL_REQUEST §R1 |
| 6 | PlayerUrlResolver | Interception of /player/url, trial mode identification, song metadata extraction, and audio replacement | M2 | ORIGINAL_REQUEST §R1 |
| 7 | Hook Interceptors & Facade | Decoupled CronetInterceptor & OkHttpInterceptor (with closed-stream fix), HookUrlMatcher, and NetEaseHook facade | M2 | ORIGINAL_REQUEST §R1 |
| 8 | AudioValidator | Centralized filter for teaser / offline audio IDs across all sources | M3 | ORIGINAL_REQUEST §R2 |
| 9 | NativeSources OkHttp Migration | Elimination of all raw HttpURLConnection in NativeSources, migrating to UnmHttpClient | M3 | ORIGINAL_REQUEST §R2 |
| 10 | QuickJsEngine Lifecycle | Persistent / reusable QuickJS runtime with single-threaded dispatcher, bytecode precompilation, and leak-free cleanup | M3 | ORIGINAL_REQUEST §R2 |
| 11 | RuleManager Validation & Rollback | JS dry-run syntax pre-check, atomic script file replacement, remote update, and embedded default rollback | M3 | ORIGINAL_REQUEST §R2 |
| 12 | Comprehensive 4-Tier Test Suite | Complete unit test coverage across Codec, Domain, Source/Engine, and E2E Hook layers with mocks | M4 | ORIGINAL_REQUEST §R3 |
| 13 | Build Verification & Compatibility | 100% pass of ./gradlew test, ./gradlew assembleDebug, UI settings contract preservation | M4 | ORIGINAL_REQUEST §Acceptance Criteria |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Core Infrastructure & Codec Layer | `UnmLogger`, `UnmLruCache`, `UnmHttpClient`, `GzipCodec`, `ProtocolCodec`, enhanced `EApiCrypto` | none | DONE |
| M2 | Domain Transformer & Hook Layer Decoupling | `SongRightsTransformer`, `PlayerUrlResolver`, `CronetInterceptor`, `OkHttpInterceptor`, `HookUrlMatcher`, `NetEaseHook` Facade | M1 | DONE |
| M3 | Engine Lifecycle & Sound Source Infrastructure | `AudioValidator`, `NativeSources` (OkHttp), `QuickJsEngine` optimization, `RuleManager` dry-run & atomic update | M1 | DONE |
| M4 | Comprehensive Testing & Final Verification | Full E2E & 4-Tier test pass, `./gradlew test` (100% pass), `./gradlew assembleDebug` APK verification | M1, M2, M3 | DONE |

## Interface Contracts

### 1. `pochita.common.UnmLogger`
```kotlin
object UnmLogger {
    interface Adapter {
        fun log(level: Int, tag: String, message: String, throwable: Throwable? = null)
    }
    fun setAdapter(adapter: Adapter)
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, tr: Throwable? = null)
    fun e(tag: String, msg: String, tr: Throwable? = null)
}
```

### 2. `pochita.common.UnmLruCache<K, V>`
```kotlin
class UnmLruCache<K : Any, V : Any>(val maxSize: Int) {
    fun get(key: K): V?
    fun put(key: K, value: V): V?
    fun remove(key: K): V?
    fun getOrPut(key: K, defaultValue: () -> V): V
    fun clear()
    fun size(): Int
}
```

### 3. `pochita.common.UnmHttpClient`
```kotlin
object UnmHttpClient {
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 10000): HttpResponse
    fun post(url: String, body: ByteArray, contentType: String = "application/json", headers: Map<String, String> = emptyMap(), timeoutMs: Long = 10000): HttpResponse
    data class HttpResponse(val code: Int, val headers: Map<String, List<String>>, val bodyBytes: ByteArray) {
        val bodyString: String get() = String(bodyBytes, Charsets.UTF_8)
        val isSuccessful: Boolean get() = code in 200..299
    }
}
```

### 4. `pochita.codec.ProtocolCodec`
```kotlin
object ProtocolCodec {
    fun isEApiEncrypted(url: String, body: ByteArray): Boolean
    fun decryptEApiResponse(cipherTextHex: String): String?
    fun encryptEApiResponse(plainText: String): String
    fun decompressGzip(compressed: ByteArray): ByteArray
    fun compressGzip(raw: ByteArray): ByteArray
}
```

### 5. `pochita.domain.transformer.SongRightsTransformer`
```kotlin
object SongRightsTransformer {
    data class TransformResult(val modifiedJson: String, val hasModified: Boolean, val discoveredSongs: List<SongInfo>)
    fun transformResponseJson(jsonStr: String): TransformResult
}
```

### 6. `pochita.resolver.PlayerUrlResolver`
```kotlin
class PlayerUrlResolver(
    private val songCache: UnmLruCache<Long, SongInfo>,
    private val audioMatcher: (SongInfo) -> MatchedAudio?
) {
    fun processPlayerUrl(url: String, responseJson: String): String?
}
```

## Code Layout
```
app/src/main/java/pochita/
├── common/
│   ├── UnmLogger.kt            # Unified logging facade
│   ├── UnmLruCache.kt          # Thread-safe concurrent LRU cache
│   └── UnmHttpClient.kt        # Lightweight OkHttp wrapper
├── codec/
│   ├── GzipCodec.kt            # Deflate & GZIP compression/decompression
│   ├── EApiCrypto.kt           # AES-128-ECB and MD5 crypto
│   └── ProtocolCodec.kt        # Combined protocol codec orchestrator
├── domain/
│   └── transformer/
│       └── SongRightsTransformer.kt # Pure recursive JSON rights rewriting
├── resolver/
│   └── PlayerUrlResolver.kt    # /player/url replacement & song metadata resolver
├── hook/
│   ├── MainHook.kt             # LSPosed module entry point
│   ├── NetEaseHook.kt          # Facade & Hook lifecycle coordinator
│   ├── HookUrlMatcher.kt       # URL route matching
│   ├── CronetInterceptor.kt    # CronetHttpURLConnection reflection & interceptor
│   └── OkHttpInterceptor.kt    # RealCall reflection & interceptor (with closed stream fix)
├── model/
│   └── SongModels.kt           # SongInfo & MatchedAudio data classes
├── rule/
│   └── RuleManager.kt          # Rule update, dry-run validation, atomic file swap
└── source/
    ├── AudioValidator.kt       # Unified teaser / offline audio blacklist
    ├── QuickJsEngine.kt        # Reusable QuickJS engine with single-threaded dispatcher
    └── NativeSources.kt        # Bodian, Kuwo, Migu, Kugou sources (using UnmHttpClient)

app/src/test/java/pochita/
├── common/
│   ├── UnmLruCacheTest.kt
│   └── UnmHttpClientTest.kt
├── codec/
│   ├── EApiCryptoTest.kt
│   └── ProtocolCodecTest.kt
├── domain/
│   └── SongRightsTransformerTest.kt
├── resolver/
│   └── PlayerUrlResolverTest.kt
├── hook/
│   └── NetEaseHookTest.kt
├── source/
│   ├── AudioValidatorTest.kt
│   ├── QuickJsEngineTest.kt
│   └── SourceMatchTest.kt
├── rule/
│   └── RuleManagerTest.kt
└── e2e/
    └── UnmE2ETest.kt
```
