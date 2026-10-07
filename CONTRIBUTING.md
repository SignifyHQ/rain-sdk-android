# Contributing

## Prerequisites

- JDK 17+ on `PATH` (AGP 9.4). Unit tests fork a JDK 24 launcher; Gradle auto-provisions
  it through the foojay resolver, so there is nothing extra to install.
- Android SDK — point `sdk.dir` at it in `local.properties`, or set `ANDROID_HOME`.

## Build & test

```bash
./gradlew build                                       # everything
./gradlew testDebugUnitTest checkNoSkippedUnitTests   # unit tests + skipped-test gate (the Test check)
```

## What CI runs

Every PR gets five checks, all required for a merge into `main`:

| Check | What it does | Locally |
|---|---|---|
| Test | Unit tests for every module, the skipped-test gate, then the vendor-free classpath check (core resolves no wallet vendor SDK, each adapter only its own). | `./gradlew testDebugUnitTest checkNoSkippedUnitTests checkVendorFreeClasspaths` |
| Analysis | detekt with type resolution (ktlint formatting rules included), Android Lint and Dokka, as separate steps after one compile. The later steps still run when an earlier one fails, so the red step names the tool. | `./gradlew detektMain detektTest lint dokkaGenerate` |
| R8 | A minified release build of the sample app against the SDK modules, then a check that R8 reported no missing classes. The shrunk app is not run. | `./gradlew :app:assembleRelease`, then `app/build/outputs/mapping/release/missing_rules.txt` must be empty |
| Release | Lints the workflow files, checks that every check name the ruleset requires exists as a job, and refuses path filters on the triggers. When `rain-sdk` in `gradle/libs.versions.toml` changes it also checks the release files: version shape, a dated `CHANGELOG.md` heading with content, the install lines in the Markdown docs, and that the version is on neither Maven Central nor the tags. | Nothing to run; it reads the repo and GitHub |
| Changelog | A change under a module's `src/main` carries an entry under `## [Unreleased]` in `CHANGELOG.md`; the `skip-changelog` label opts a PR out. | Nothing to run |

Gradle's build cache is on. Runs on `main` write it and PRs read it, so a PR that touches one module
does not recompile or re-test the others; locally the same cache lives under `~/.gradle`.

Two rules keep the checks from stranding every open PR. The ruleset lists checks by job name, so
renaming or removing a job in `.github/workflows` needs an admin to update the ruleset first
(Settings > Rules), and the Release check fails a PR that forgets. And the triggers carry no `paths`
filters: a PR the filter skips gets no run and no check, which blocks its merge.

## Code style & static analysis

The Analysis check runs detekt (including ktlint formatting rules), Android Lint and Dokka. Run the
same things locally before pushing:

```bash
./gradlew detektMain detektTest                       # the detekt step of the Analysis check
./gradlew detektMain detektTest -PdetektAutoCorrect   # auto-fixes formatting findings in place; commit the result
./gradlew lint dokkaGenerate                          # the other two steps
```

The code style is `intellij_idea` (ktlint's default), set as `ktlint.code_style` in
`config/detekt/detekt.yml` and mirrored in `.editorconfig`, with a 4-space indent and 150-column
lines — Android Studio's *Reformat Code* produces compliant output.

Non-formatting findings (null-safety, swallowed exceptions, complexity, magic numbers)
cannot be auto-fixed: fix the code, or `@Suppress("RuleName")` at the smallest possible
scope with a reason. Never add entries to a `detekt-baseline.xml` — baselines may only
shrink, and the Analysis check fails if one grows.

## Before opening a PR

- Everything above green locally.
- Commits must be **signed**, and authored with an email linked to your GitHub account —
  the branch ruleset refuses to merge unsigned or unattributed commits.
- If you changed the public API, update `docs/METHODS.md` to match.
- If you changed anything under a module's `src/main`, add a line under `## [Unreleased]` in
  `CHANGELOG.md` (Added, Changed, Deprecated, Removed or Fixed; a line a host must act on starts
  with **Breaking:** and a migration note). The Changelog check fails the PR otherwise. For a
  change no host can notice, add the `skip-changelog` label instead.
- A new member on `WalletProvider`, `ProviderDescriptor` or `RainClient` ships with a default body
  wherever one makes sense. Hosts implement these interfaces behind `@ExperimentalRainApi`, and an
  abstract member breaks every host implementation at compile time.
