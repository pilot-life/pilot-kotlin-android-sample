import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Resolve secrets at build time. Precedence (highest first):
//   1. -P gradle property (e.g. `./gradlew assembleDebug -PPILOT_API_KEY=…`)
//   2. environment variable (handy on CI)
//   3. local.properties (gitignored — dev-local)
//   4. empty string fallback (lets debug builds compile without secrets;
//      runtime calls will 401 against the real API)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    (providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: localProps.getProperty(name)
        ?: "")

android {
    namespace = "life.pilot.partner.testapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "life.pilot.partner.testapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "PILOT_API_KEY", "\"${secret("PILOT_API_KEY")}\"")
        buildConfigField("String", "PILOT_ORG_UUID", "\"${secret("PILOT_ORG_UUID")}\"")
        buildConfigField("String", "PILOT_GATEWAY_SECRET", "\"${secret("PILOT_GATEWAY_SECRET")}\"")
        buildConfigField("String", "PILOT_ENVIRONMENT", "\"${secret("PILOT_ENVIRONMENT").ifBlank { "SANDBOX" }}\"")
        // Optional explicit base URL — when set, takes precedence over
        // PILOT_ENVIRONMENT. Useful for pointing at localhost (use
        // http://10.0.2.2:PORT/partner/v1/ from the Android emulator)
        // or a partner-side mock.
        buildConfigField("String", "PILOT_BASE_URL", "\"${secret("PILOT_BASE_URL")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    // Consume the SDK + UI library published to ~/.m2 by pilot-kotlin's
    // `publishToMavenLocal`. Real partners would swap mavenLocal() for
    // GitHub Packages.
    implementation("life.pilot:pilot-partner-sdk:0.1.0-SNAPSHOT")
    implementation("life.pilot:pilot-partner-ui-compose:0.1.0-SNAPSHOT")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
