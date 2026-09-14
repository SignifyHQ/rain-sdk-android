import java.util.Properties
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        // Core's own cross-module seams are marked @RainAdapterApi. This module declares and
        // uses them, so it opts in as a whole; host apps do not.
        freeCompilerArgs += listOf("-opt-in=com.rain.sdk.internal.RainAdapterApi")
    }
}

// This module needs Bouncy Castle at runtime: web3j's crypto helpers (Keccak hashing behind
// `RainHexUtils.validateAndChecksum` and the Solana withdrawal path) call into it. It is declared
// directly below rather than inherited from a wallet vendor's transitive graph, so a Portal-only
// or Privy-only consumer still gets it.
//
// Two `org.bouncycastle.*` builds exist and ship the same class names, so dex-ing both fails with
// "Duplicate class" errors. The project standardizes on `bcprov-jdk15to18` and excludes web3j's
// parallel `bcprov-jdk18on:1.73`. The targeted exclusion below (on the web3j dependency) is also
// published into Gradle Module Metadata so downstream Gradle consumers inherit it automatically.
// The configurations-wide exclusion is belt-and-suspenders for direct module builds and non-Gradle
// consumers — see docs/TURNKEY_SUPPORT.md for the Maven-POM workaround.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

dependencies {
    // Bouncy Castle: web3j's Keccak hashing needs it, so core owns the dependency and its CVE
    // floor rather than inheriting either from a wallet vendor. `implementation` because no public
    // signature names a BC type. Constraints publish into Gradle Module Metadata, so downstream
    // consumers inherit the floor; they only raise versions, never downgrade.
    //   bcprov-jdk15to18 < 1.84 -> GHSA-574f-3g2m-x479 (GOST 28147 CTR keystream reuse, critical)
    constraints {
        implementation(libs.bouncycastle.bcprov) {
            because(
                "GHSA-574f-3g2m-x479: GOST 28147 CTR keystream reuse in bcprov < 1.84. " +
                    "bcprov-jdk15to18 is the single BC artifact on the classpath (it replaces " +
                    "web3j's bcprov-jdk18on:1.73)"
            )
        }
    }

    implementation(libs.bouncycastle.bcprov)

    // Web3j for ABI Encoding. See note above about Bouncy Castle conflict.
    implementation(libs.web3j.core) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    }

    // JSON Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Timber
    implementation(libs.timber)

    // AndroidX Annotations (for @VisibleForTesting)
    implementation(libs.androidx.annotation)

    // Networking
    implementation(libs.okhttp)
    // implementation(libs.okhttp.logging)  // Will be enabled in Phase 4
    
    // Utilities
    implementation(libs.zxing.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Already on the test runtime classpath via mockk; declared so reflection compiles cleanly.
    testImplementation(kotlin("reflect"))
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json implementation for unit tests — production code uses Android's bundled
    // org.json (which AGP stubs on the test JVM and makes throw "not mocked" at runtime).
    testImplementation(libs.json)
    // Reference implementation of Solana address/transaction encoding, used ONLY as a test
    // oracle: the SDK's own `internal.solana` package is asserted byte-for-byte against it
    // (SolanaAddressesTest, SolanaTransactionBuilderTest). Deliberately test-scoped — this is a
    // published artifact, so consumers must not inherit a Solana library they don't call.
    testImplementation(libs.sol4k) {
        // sol4k publishes against kotlin-stdlib 2.4.0, whose metadata this project's Kotlin 2.2
        // compiler cannot read. Gradle would otherwise raise the whole test classpath to it and
        // fail every test compile. The project's own stdlib is enough to run sol4k's encoders.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    testImplementation(libs.sol4k.tweetnacl)
}

mavenPublishing {
    coordinates("io.github.spartan-quanhongtran", "rain-core-android", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Core")
        description.set("Vendor-free core of the Rain Android SDK (port, registry, domain logic)")
        url.set("https://github.com/SignifyHQ/rain-sdk-android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("spartan-quanhongtran")
                name.set("spartan-quanhongtran")
                email.set("engineering@signify.net")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/SignifyHQ/rain-sdk-android.git")
            developerConnection.set("scm:git:ssh://github.com/SignifyHQ/rain-sdk-android.git")
            url.set("https://github.com/SignifyHQ/rain-sdk-android")
        }
    }

    // Configure publishing to Sonatype Central Portal (Standard for new accounts 2024+)
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Enable signing (will use memory keys from local.properties or env vars)
    signAllPublications()
}
