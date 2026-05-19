# pilot-kotlin-test

A minimal Android app that integrates the
[`life.pilot:pilot-partner-sdk`](https://github.com/pilot-life/pilot-kotlin) and
`life.pilot:pilot-partner-ui-compose` artifacts end-to-end. Used as a
canary to catch consumer-facing issues (transitive scope leaks, missing
R8 rules, runtime serialization failures) before they reach partners.

## What it does

- `MainActivity` wires a real `EventList` → `EventDetailScreen` → `CheckoutSheet`
  flow using `EventsViewModel`, hitting the SDK at every step.
- `PartnerClientHolder` is the process-wide singleton client (mirrors
  what the integration guide recommends).
- `SdkIntegrationSmokeTest` exercises the SDK from the consumer
  classpath against `MockWebServer` — list events, claim → checkout,
  `PartnerException.SoldOut` propagation, webhook parsing + HMAC
  verification.

## Build

```bash
# 1. Publish the SDK + UI library locally first
(cd ../pilot-kotlin && ./gradlew publishToMavenLocal)

# 2. Then build / test this app
./gradlew :app:assembleDebug              # debug APK
./gradlew :app:assembleRelease            # release APK with R8 minification
./gradlew :app:testDebugUnitTest          # consumer JVM smoke tests
```

The release build is intentionally configured with
`isMinifyEnabled = true` and an empty `proguard-rules.pro` — it relies
entirely on the consumer R8 rules shipped inside the SDK / UI artifacts.
If a future SDK change strips required serializer rules, the release
build will fail here first.

## Repo layout

```
app/
├── build.gradle.kts                       # depends on mavenLocal SDK + UI
└── src/
    ├── main/kotlin/.../MainActivity.kt    # full list → detail → checkout flow
    ├── main/kotlin/.../PartnerClientHolder.kt
    └── test/kotlin/.../SdkIntegrationSmokeTest.kt
```

## See also

- SDK + UI library source: [pilot-life/pilot-kotlin](https://github.com/pilot-life/pilot-kotlin)
- Integration guide (lives in the library repo): `docs/integration-guide.md`
