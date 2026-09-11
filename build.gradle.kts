// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka) apply false
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

// Unit tests run on a JDK 24 launcher (Gradle/AGP/Kotlin stay on the invoking JDK) because the
// Turnkey AAR ships class-file 68; on an older JVM the Turnkey suites silently `assume`-skip.
// Override with -Prain.testJdk=21 to reproduce that behaviour on purpose.
val rainTestJdk = (findProperty("rain.testJdk") as String?)?.toInt() ?: 24
subprojects {
    tasks.withType<Test>().configureEach {
        javaLauncher.set(
            project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(rainTestJdk))
            }
        )
    }
}

// Tests that are env-gated on purpose (live network) and therefore allowed to skip.
val allowedSkippedTests = setOf(
    "com.rain.sdk.internal.transaction.RainTransactionBuilderImplTest" +
        ".getLatestNonce uses real network and returns nonce gt 0"
)
val sdkTestModules =
    listOf("rain-core-android", "rain-turnkey-android", "rain-portal-android", "rain-privy-android")

// detekt: static analysis on all modules, defaults + config/detekt/detekt.yml overrides.
// CI runs the type-resolving tasks (`detektMain detektTest`) — the bare `detekt` task has
// no classpath and silently skips the type-resolution rules (UnsafeCallOnNullableType etc.).
// Pre-existing findings live in each module's detekt-baseline.xml, shared across variants.
// Regenerate only when adopting a module: `./gradlew detektBaselineMain detektBaselineTest`,
// then fold the emitted detekt-baseline-<variant>.xml files back into detekt-baseline.xml
// (variant-suffixed files take precedence over the base file if left behind — don't keep them).
// Baselines should only shrink (CI enforces this); fix new findings or @Suppress at the
// smallest scope. NOTE: a baseline entry waives its whole (rule, class, expression) triple,
// not one occurrence — a new identical catch in a baselined class would pass silently.
// Captured at root scope: the type-safe `libs` accessor does not resolve inside
// a `subprojects {}` block (it evaluates against the subproject, which has no catalog).
val detektFormatting = libs.detekt.formatting

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        parallel = true
        // Formatting violations are machine-fixable: run
        //   ./gradlew detektMain detektTest -PdetektAutoCorrect
        // and commit the result. Gated behind the property so a plain check run
        // never rewrites the working tree.
        autoCorrect = providers.gradleProperty("detektAutoCorrect").isPresent
    }
    dependencies {
        // ktlint rules (android_studio code style, see .editorconfig) inside detekt.
        "detektPlugins"(detektFormatting)
    }

    // Dokka is a CI check (dokkaHtml leg): with the 1.9.20 defaults it can only fail on a
    // total generation error, never on a broken KDoc link — failOnWarning gives it teeth.
    plugins.withId("org.jetbrains.dokka") {
        tasks.withType<org.jetbrains.dokka.gradle.AbstractDokkaTask>().configureEach {
            failOnWarning.set(true)
        }
    }
}

// Fails the build if any unit test skipped, so JDK-gated Turnkey suites can't pass by not running.
tasks.register("checkNoSkippedUnitTests") {
    group = "verification"
    description = "Fails if any SDK unit test was skipped (allowlisted env-gated tests excepted)."
    mustRunAfter(sdkTestModules.map { ":$it:testDebugUnitTest" })
    val resultDirs = sdkTestModules.map { it to project(it).layout.buildDirectory.dir("test-results/testDebugUnitTest") }
    val allowed = allowedSkippedTests
    val summaryFile = System.getenv("GITHUB_STEP_SUMMARY")
    doLast {
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        var tests = 0
        var failures = 0
        val skipped = mutableListOf<String>()
        val perModule = linkedMapOf<String, Pair<Int, Int>>()
        resultDirs.forEach { (module, dirProvider) ->
            val dir = dirProvider.get().asFile
            val files = dir.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }.orEmpty()
            require(files.isNotEmpty()) { "No test results in $dir — run testDebugUnitTest first." }
            var moduleTests = 0
            var moduleSkipped = 0
            files.forEach { file ->
                val doc = parser.parse(file)
                val suite = doc.documentElement
                val cases = suite.getElementsByTagName("testcase")
                for (i in 0 until cases.length) {
                    val case = cases.item(i) as org.w3c.dom.Element
                    tests++; moduleTests++
                    val id = "${case.getAttribute("classname")}.${case.getAttribute("name")}"
                    if (case.getElementsByTagName("skipped").length > 0) {
                        moduleSkipped++
                        if (id !in allowed) skipped += id
                    }
                    if (case.getElementsByTagName("failure").length > 0 ||
                        case.getElementsByTagName("error").length > 0) failures++
                }
            }
            perModule[module] = moduleTests to moduleSkipped
        }
        val allowedHits = perModule.values.sumOf { it.second } - skipped.size
        val summary = buildString {
            appendLine("## Unit tests (JDK $rainTestJdk launcher)")
            appendLine()
            appendLine("| Module | Tests | Skipped |")
            appendLine("|---|---:|---:|")
            perModule.forEach { (m, c) -> appendLine("| $m | ${c.first} | ${c.second} |") }
            appendLine("| **total** | **$tests** | **${skipped.size}** (+$allowedHits allowlisted) |")
            if (failures > 0) appendLine("\n**failures=$failures**")
            if (skipped.isNotEmpty()) {
                appendLine("\nUnexpected skips:")
                skipped.forEach { appendLine("- `$it`") }
            }
        }
        println("tests=$tests skipped=${skipped.size} allowlistedSkips=$allowedHits failures=$failures")
        println(summary)
        summaryFile?.let { File(it).appendText(summary + "\n") }
        if (skipped.isNotEmpty()) {
            throw GradleException(
                "${skipped.size} unit test(s) were skipped — tests are running on the wrong JDK or a new " +
                    "env-gated test needs allowlisting:\n" + skipped.joinToString("\n") { "  $it" }
            )
        }
    }
}
