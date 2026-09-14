import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk.turnkey"
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
        freeCompilerArgs += listOf(
            // The managed authentication surface is marked @InternalRainTurnkeyApi (a @RequiresOptIn
            // marker at error level). This module defines and uses it, so it opts in as a whole;
            // host apps do not.
            "-opt-in=com.rain.sdk.turnkey.InternalRainTurnkeyApi",
            // Core's cross-module seams (chain readers, RPC clients, Solana encoders) are marked
            // @RainAdapterApi. Adapter modules are the intended callers, so this one opts in.
            "-opt-in=com.rain.sdk.internal.RainAdapterApi",
        )
    }
}

// Two `org.bouncycastle.*` builds ship the same class names, so dex-ing both fails with
// "Duplicate class" errors. The project standardizes on `bcprov-jdk15to18` — which the Turnkey
// artifacts below are compiled against — and excludes web3j's parallel `bcprov-jdk18on`. The root
// build applies this to every module; kept here too for direct module builds.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

dependencies {
    // Vendor-free core: exposes the WalletProvider port, models, registry, and domain logic.
    api(project(":rain-core-android"))

    // Security: force Turnkey's transitive deps above their CVE-affected versions. Constraints
    // publish into Gradle Module Metadata, so downstream consumers inherit the bumped versions;
    // they only raise versions, never downgrade. Both reach the classpath solely through Turnkey,
    // which is why the floors live with the adapter rather than in core.
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

    // Turnkey SDK. `api` on the Kotlin SDK because TurnkeyConfig exposes the `TurnkeyContext` type
    // to consumers in bring-your-own mode — the host authenticates it and hands it in. `http` and
    // `types` carry no type into a Rain public signature, so they are declared
    // `implementation`; note that sdk-kotlin already re-exports both in its own module metadata,
    // so this is bookkeeping rather than isolation.
    api(libs.turnkey.sdk.kotlin)
    implementation(libs.turnkey.http)
    implementation(libs.turnkey.types)

    // The indexed-history client parses Turnkey's JSON responses.
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.androidx.annotation)
    implementation(libs.okhttp)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    // Already on the test runtime classpath via mockk; declared so reflection compiles cleanly.
    testImplementation(kotlin("reflect"))
}

mavenPublishing {
    coordinates("io.github.spartan-quanhongtran", "rain-turnkey-android", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Turnkey adapter")
        description.set("Turnkey embedded-wallet provider for the Rain Android SDK")
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

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
}
