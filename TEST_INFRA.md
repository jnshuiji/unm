# E2E Test Infra: NetEase UNM

## Test Philosophy
- Opaque-box, requirement-driven. No dependency on implementation design.
- Methodology: Category-Partition + BVA + Pairwise + Workload Testing.

## Feature Inventory
| # | Feature | Source (requirement) | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
|---|---------|---------------------|:------:|:------:|:------:|:------:|
| 1 | UnmLogger Facade | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 2 | UnmLruCache | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 3 | UnmHttpClient | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 4 | Protocol & Codec (EAPI/GZIP) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 5 | SongRightsTransformer | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 6 | PlayerUrlResolver | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 7 | Hook Interceptors & Streams | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 8 | AudioValidator (Teaser Filter) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 9 | NativeSources (OkHttp) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 10 | QuickJsEngine Lifecycle | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 11 | RuleManager Validation & Rollback | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 12 | Overall Build & Compatibility | ORIGINAL_REQUEST §Acceptance | 5 | 5 | ✓ | ✓ |

## Test Architecture
- Test runner: `./gradlew test` (JUnit4 local unit tests on OpenJDK 25)
- Debug APK build: `./gradlew assembleDebug`
- Test case directory layout:
  - `app/src/test/java/pochita/common/`
  - `app/src/test/java/pochita/codec/`
  - `app/src/test/java/pochita/domain/`
  - `app/src/test/java/pochita/resolver/`
  - `app/src/test/java/pochita/hook/`
  - `app/src/test/java/pochita/source/`
  - `app/src/test/java/pochita/rule/`
  - `app/src/test/java/pochita/e2e/`

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | GZIP EAPI Playlist Response Unblocking | Codec, GZIP, RightsTransformer, Cache | High |
| 2 | Vip Song Trial Mode /player/url Interception & Audio Replacement | Hook, Resolver, RuleManager, NativeSources, AudioValidator | High |
| 3 | QuickJS Engine Concurrency & Reusability Under Heavy Playlist Query | QuickJsEngine, UnmLruCache, UnmHttpClient, UnmLogger | High |
| 4 | Corrupted EAPI & Truncated GZIP Network Stream Graceful Fallback | Hook, Codec, Error Isolation, Logger | High |
| 5 | Remote Rule Update With Malformed Script & Automatic Rollback | RuleManager, QuickJsEngine, DataRepository contract | Medium |

## Coverage Thresholds
- Tier 1: ≥5 per feature (Happy-path isolated unit tests)
- Tier 2: ≥5 per feature (Boundary, corner, malformed, empty, negative cases)
- Tier 3: Pairwise coverage of major cross-feature interactions
- Tier 4: ≥5 realistic application-level scenarios

