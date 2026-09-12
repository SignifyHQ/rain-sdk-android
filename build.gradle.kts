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

// Two Bouncy Castle builds ship the same `org.bouncycastle.*` class names, so dex-ing both fails
// with "Duplicate class" errors. The project standardizes on `bcprov-jdk15to18`, which core declares
// directly (web3j's Keccak needs it) and the Turnkey artifacts are compiled against; web3j's parallel
// `bcprov-jdk18on:1.73` is excluded everywhere.
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

// Which wallet vendor SDK each published module may carry on its release compile and runtime
// classpaths. The modular split promises that core carries none and each adapter carries only its
// own; a build that merely compiles is no proof of that, because a vendor left on core's classpath
// is simply unused there. Every module that applies the Android library plugin must be listed.
val vendorGroupIds = setOf("com.turnkey", "io.portalhq", "io.privy")
val allowedVendorGroups = mapOf(
    "rain-core-android" to emptySet(),
    "rain-turnkey-android" to setOf("com.turnkey"),
    "rain-portal-android" to setOf("io.portalhq"),
    "rain-privy-android" to setOf("io.privy"),
)
require(allowedVendorGroups.values.flatten().all { it in vendorGroupIds }) {
    "allowedVendorGroups names a vendor group missing from vendorGroupIds, " +
        "so the check would never look for it in another module"
}

subprojects {
    plugins.withId("com.android.library") {
        // Locals only: the task action must not capture the build script (a Project), or the
        // configuration cache cannot serialize it.
        val moduleName = name
        val allowed = allowedVendorGroups[moduleName]
            ?: throw GradleException("$moduleName applies com.android.library but has no allowedVendorGroups entry")
        val forbidden = vendorGroupIds - allowed
        val permitted = if (allowed.isEmpty()) "no wallet vendor SDK" else "only ${allowed.joinToString()}"
        tasks.register("checkVendorFreeClasspath") {
            group = "verification"
            description = "Fails if this module's release classpaths carry a wallet vendor SDK it must not ship."
            // Resolution results are captured as providers when the task graph realizes this task,
            // after the Android plugin has created the variant configurations, and read in doLast.
            val roots = listOf("releaseCompileClasspath", "releaseRuntimeClasspath").map { cfg ->
                cfg to configurations.named(cfg).flatMap { it.incoming.resolutionResult.rootComponent }
            }
            doLast {
                // Walks the resolved graph and returns the first path from the root to each leaked
                // group, so the failure message says which dependency introduced the vendor. An
                // unresolved edge fails the check outright: a vendor coordinate that failed to resolve
                // must not pass as absent.
                fun leakPaths(cfg: String, root: org.gradle.api.artifacts.result.ResolvedComponentResult): Map<String, String> {
                    val found = linkedMapOf<String, String>()
                    val seen = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
                    fun visit(node: org.gradle.api.artifacts.result.ResolvedComponentResult, path: List<String>) {
                        if (!seen.add(node)) return
                        node.dependencies.filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>().firstOrNull()?.let {
                            throw GradleException(
                                "$moduleName: $cfg has an unresolved dependency, so the vendor check cannot vouch for it: " +
                                    "${it.requested.displayName} (${it.failure.message})"
                            )
                        }
                        node.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { dep ->
                            val id = dep.selected.id
                            val label = id.displayName
                            val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
                            val leaked = group != null && forbidden.any { vendor -> group == vendor || group.startsWith("$vendor.") }
                            if (leaked && group !in found) found[group!!] = (path + label).joinToString(" -> ")
                            visit(dep.selected, path + label)
                        }
                    }
                    visit(root, listOf(moduleName))
                    return found
                }
                roots.forEach { (cfg, rootProvider) ->
                    val leaks = leakPaths(cfg, rootProvider.get())
                    if (leaks.isNotEmpty()) {
                        throw GradleException(
                            "$moduleName carries a wallet vendor SDK it must not ship on $cfg. This module may carry " +
                                "$permitted. Leaked:\n" + leaks.values.joinToString("\n") { "  $it" }
                        )
                    }
                }
                println("$moduleName: compile and runtime classpaths carry $permitted")
            }
        }
        tasks.named("check") { dependsOn("checkVendorFreeClasspath") }
    }
}

tasks.register("checkVendorFreeClasspaths") {
    group = "verification"
    description = "Runs every module's vendor-free classpath check."
    dependsOn(allowedVendorGroups.keys.map { ":$it:checkVendorFreeClasspath" })
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
