// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Turnkey (com.turnkey:crypto, com.turnkey:encoding) depends on Bouncy Castle's
// `bcprov-jdk15to18:1.82`. Web3j 4.10 depends on the parallel `bcprov-jdk18on:1.73` build.
// Both artifacts publish the same `org.bouncycastle.*` class names, so dex-ing them together
// fails with "Duplicate class" errors. Force every module onto a single BC artifact
// (Turnkey's, since Turnkey was compiled against it) by excluding the duplicate everywhere.
subprojects {
    configurations.all {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    }
}
