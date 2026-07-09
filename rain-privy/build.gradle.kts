import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk.privy"
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

dependencies {
    // Vendor-free core: the WalletProvider port, models, registry, and domain logic.
    api(project(":rain-core"))

    // Privy SDK. `api` (not `implementation`) because PrivyConfig exposes the `Privy` type to
    // consumers — the host initializes/authenticates Privy and hands the singleton in, mirroring
    // how rain-core's TurnkeyConfig exposes TurnkeyContext.
    api(libs.privy.core)

    // Web3j for ERC-20 ABI encoding in sendToken calldata. Shares core's Bouncy Castle note.
    implementation(libs.web3j.core) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    }

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.androidx.annotation)
    // Own JSON-RPC read path: core's JsonRpcClient/ChainReader are module-internal, so out-of-module
    // adapters can't reuse them. Privy's EIP-1193 provider handles custody (sign/send) only.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
}

mavenPublishing {
    coordinates("io.github.spartan-quanhongtran", "rain-privy", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Privy adapter")
        description.set("Privy embedded-wallet provider for the Rain Android SDK")
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
