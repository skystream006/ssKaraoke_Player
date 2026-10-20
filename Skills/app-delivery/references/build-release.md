# Build, versioning and release automation

## Configuration map

| File | Authority |
| --- | --- |
| [settings.gradle.kts](../../../settings.gradle.kts) | One `:app` module and dependency repositories |
| [build.gradle.kts](../../../build.gradle.kts) | Android/Kotlin plugin versions |
| [app/build.gradle.kts](../../../app/build.gradle.kts) | SDK levels, application ID, dependencies, BuildConfig, signing, version derivation and test/lint options |
| [gradle.properties](../../../gradle.properties) | JVM, AndroidX and Kotlin style settings |
| [wrapper properties](../../../gradle/wrapper/gradle-wrapper.properties), [gradlew](../../../gradlew), [gradlew.bat](../../../gradlew.bat) | Pinned Gradle distribution/checksum and platform entry points |
| [version.properties](../../../version.properties) | Fixed first-app revision and version baseline |
| [package.json](../../../package.json), [package-lock.json](../../../package-lock.json) | Locked Node test dependencies and scripts, not a production web bundle |
| [playwright.config.cjs](../../../playwright.config.cjs) | Browser projects, fixture server and reports |
| [manual-release.yml](../../../.github/workflows/manual-release.yml) | Full-history main checkout, tests/build/signing verification and manual publishing |
| [release-reminder.yml](../../../.github/workflows/release-reminder.yml) | Post-merge reminder, no checkout/execution of PR code |
| [cleanup-releases.yml](../../../.github/workflows/cleanup-releases.yml) | Scheduled/manual retention of five newest releases and safe tag cleanup |

Current toolchain is Java 17, Gradle 8.13, AGP 8.13.2 and Kotlin 2.2.21;
minimum Android API 26, compile/target API 36. Read the authoritative
configuration when changing versions instead of assuming this snapshot stays
current. Setup and build commands live in [validation](../../repo-router/references/validation.md).

## Version/signing contract

- Gradle rejects shallow history during configuration. `versionCode` comes
  from first-parent revision count; version name advances from the fixed
  baseline. Do not add version-bump commits or use total-history counts.
- `com.sskaraoke.player` and the signing identity must remain compatible with
  installed copies. Preserve the checked-in public development key; it is
  not a secure production identity.
- Optional distribution signing uses `APK_SIGNING_STORE_FILE`,
  `APK_SIGNING_STORE_PASSWORD`, `APK_SIGNING_KEY_ALIAS` and
  `APK_SIGNING_KEY_PASSWORD` locally. Actions supplies the keystore via
  `APK_SIGNING_KEYSTORE_BASE64`; all four Actions secrets must be set or absent.
  Do not read/copy private key contents into context or commit secrets.
- Manual release checks out latest main with full history, runs Node/browser
  and native checks, verifies the APK signer, names the universal asset and
  publishes its checksum. Existing published versions are left unchanged.
- The updater consumes Releases, not zipped Actions artifacts. Coordinate
  asset/version changes with [runtime](runtime.md), including `ReleaseInfo`.

## Workflow boundaries

Open only the implicated workflow. Release-reminder uses `pull_request_target`
but must never execute or check out PR code; preserve its narrow permissions
and deduplication. Cleanup includes drafts/prereleases, deletes releases by ID,
and preserves tags used by retained releases. Its behavior is not covered by
the current workflow tests.

No production publishing or destructive cleanup is needed to validate a
documentation/configuration edit. Follow [tests](tests.md) and report gaps.
Consult webview-adapter for browser-runner changes; do not load its assets
for a signing-only change.
