# Contributing

## Prerequisites

- JDK 17+ on `PATH` (AGP 9.4). Unit tests fork a JDK 24 launcher; Gradle auto-provisions
  it through the foojay resolver, so there is nothing extra to install.
- Android SDK — point `sdk.dir` at it in `local.properties`, or set `ANDROID_HOME`.

## Build & test

```bash
./gradlew build                                       # everything
./gradlew testDebugUnitTest checkNoSkippedUnitTests   # unit tests + skipped-test gate (what CI runs)
```

## Code style & static analysis

Every PR runs a `checks` matrix in CI: detekt (including ktlint formatting rules),
Android Lint, Dokka, a minified release build of the sample app, and the vendor-free classpath
check. Run the same things locally before pushing:

```bash
./gradlew detektMain detektTest                       # what the CI detekt leg runs
./gradlew detektMain detektTest -PdetektAutoCorrect   # auto-fixes formatting findings in place — commit the result
./gradlew lint dokkaGenerate :app:assembleRelease checkVendorFreeClasspaths   # the other CI legs
```

The code style is `intellij_idea` (ktlint's default), set as `ktlint.code_style` in
`config/detekt/detekt.yml` and mirrored in `.editorconfig`, with a 4-space indent and 150-column
lines — Android Studio's *Reformat Code* produces compliant output.

Non-formatting findings (null-safety, swallowed exceptions, complexity, magic numbers)
cannot be auto-fixed: fix the code, or `@Suppress("RuleName")` at the smallest possible
scope with a reason. Never add entries to a `detekt-baseline.xml` — baselines may only
shrink, and CI fails the detekt leg if one grows.

## Before opening a PR

- Everything above green locally.
- Commits must be **signed**, and authored with an email linked to your GitHub account —
  the branch ruleset refuses to merge unsigned or unattributed commits.
- If you changed the public API, update `docs/METHODS.md` to match.
- A new member on `WalletProvider`, `ProviderDescriptor` or `RainClient` ships with a default body
  wherever one makes sense. Hosts implement these interfaces behind `@ExperimentalRainApi`, and an
  abstract member breaks every host implementation at compile time.
