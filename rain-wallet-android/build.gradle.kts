import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

version = libs.versions.rain.sdk.get()

android {
    namespace = "com.rain.sdk.wallet"
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
            // The wallet backend's managed authentication surface is marked @InternalRainTurnkeyApi
            // (a @RequiresOptIn marker at error level). This module is its intended consumer and opts
            // in as a whole; the flag, rather than @OptIn annotations, also keeps the marker's name
            // out of the rendered API docs.
            "-opt-in=com.rain.sdk.turnkey.InternalRainTurnkeyApi",
        )
    }
}

// Two `org.bouncycastle.*` builds ship the same class names, so dex-ing both fails with
// "Duplicate class" errors. The project standardizes on `bcprov-jdk15to18`; the root build applies
// this to every module and it is kept here too for direct module builds.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}

dependencies {
    // Vendor-free core: the port, models, registry and domain logic. `api`, so `RainSdk`,
    // `RainClient`, `ProviderId` and `RainError` reach a host through this one dependency.
    api(project(":rain-core-android"))

    // The wallet backend adapter. `implementation`, never `api`: nothing from it appears in this
    // module's public signatures, and keeping it off a host's compile classpath is what makes the
    // Rain wallet surface vendor-neutral. The vendor SDK still ships on the host's runtime classpath.
    implementation(project(":rain-turnkey-android"))

    // `Flow` appears in this module's public signatures (session and auth state), so the
    // coroutines library is part of its contract with hosts.
    api(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    coordinates("io.github.spartan-quanhongtran", "rain-wallet-android", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Rain wallet")
        description.set("The Rain wallet provider for the Rain Android SDK")
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
