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

    // NOTE: skeleton only. The real Privy embedded-wallet SDK will be added here as an
    // `implementation` dependency (a private detail of this module) when the adapter is built out.

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

mavenPublishing {
    coordinates("io.github.spartan-quanhongtran", "rain-privy", libs.versions.rain.sdk.get())

    pom {
        name.set("Rain SDK Android — Privy adapter")
        description.set("Privy embedded-wallet provider for the Rain Android SDK (skeleton)")
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
