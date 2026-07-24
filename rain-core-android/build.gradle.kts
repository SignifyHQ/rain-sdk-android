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
    }
}

// Turnkey (com.turnkey:crypto, com.turnkey:encoding) depends on Bouncy Castle's
// `bcprov-jdk15to18:1.82`. Web3j 4.10 depends on the parallel `bcprov-jdk18on:1.73` build.
// Both artifacts ship the same `org.bouncycastle.*` class names, so dex-ing them together
// fails with "Duplicate class" errors. Force the whole project onto a single BC artifact
// (Turnkey's, since Turnkey was compiled against it) by excluding the duplicate.
//
// The targeted exclusion below (on the web3j dependency) is also published into Gradle
// Module Metadata so downstream Gradle consumers inherit it automatically. The
// configurations-wide exclusion is belt-and-suspenders for direct module builds and
// non-Gradle consumers — see docs/TURNKEY_SUPPORT.md for the Maven-POM workaround.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

dependencies {
    // Security: force Turnkey's transitive deps above their CVE-affected versions.
    // Constraints publish into Gradle Module Metadata, so downstream consumers inherit
    // the bumped versions; they only raise versions, never downgrade.
    //   bitcoinj < 0.17.1 -> GHSA-hfcf-v2f8-x9pc (P2PKH/P2WPKH verify bypass)
    //   protobuf-javalite < 3.25.5 -> GHSA-735f-pc8j-v9w8 (DoS)
    constraints {
        api(libs.bitcoinj.core) {
            because("CVE GHSA-hfcf-v2f8-x9pc: P2PKH/P2WPKH verification bypass in bitcoinj < 0.17.1")
        }
        api(libs.protobuf.javalite) {
            because("CVE GHSA-735f-pc8j-v9w8: DoS in protobuf-javalite < 3.25.5")
        }
    }

    // Turnkey SDK (Use api to expose TurnkeyContext / types to consumers).
    // Turnkey lives inside rain-core-android for now (com.rain.sdk.turnkey) per the modular-architecture
    // migration; it will graduate to a standalone rain-turnkey module later.
    api(libs.turnkey.sdk.kotlin)
    api(libs.turnkey.http)
    api(libs.turnkey.types)

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
