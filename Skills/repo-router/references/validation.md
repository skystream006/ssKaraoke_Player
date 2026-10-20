# Shared validation commands

Use a domain's test reference to select classes/methods or test titles first.
Do not run every command below for every change. These are existing tools,
not a new test harness.

## Checkout and prerequisites

Set `REPO` to the absolute checkout root. For the cloud checkout:

```sh
REPO=/home/runner/work/ssKaraoke_Player/ssKaraoke_Player
cd "$REPO"
```

Commands below run from that directory; replace the root on other machines.
Resolve portable documentation links to absolute paths before filesystem tools.

- Android: Java 17, SDK Platform 36, and **full Git history**, including for
  unit tests. Configure `JAVA_HOME` and `ANDROID_HOME` or Android Studio's
  SDK/JDK settings. The release workflow installs Build Tools 36.0.0.
- The checked-in Gradle wrapper is the entry point; its distribution is
  checksum-pinned. Do not install a replacement build tool.
- Node 22 is recommended. Use `npm ci` when test dependencies are absent;
  `npx playwright install chromium` installs the configured browser.
- Device tests need a connected device/emulator. Real-frontend browser tests
  need an existing external build, not an assumed sibling checkout.
- Sources: [app build](../../../app/build.gradle.kts),
  [package scripts](../../../package.json), [Playwright config](../../../playwright.config.cjs),
  [README verification](../../../README.md#verification).

## Native selection

```sh
# Replace class/method with the affected test from the domain reference.
sh "$REPO/gradlew" :app:testDebugUnitTest \
  --tests 'com.sskaraoke.player.NativeUiTest.updateButtonFitsALargeFontOnANarrowPhone'

# Select a class; repeat --tests to include another impacted class.
sh "$REPO/gradlew" :app:testDebugUnitTest \
  --tests 'com.sskaraoke.player.PointerNavigationTest'

# Add for Kotlin, resources, or manifest changes.
sh "$REPO/gradlew" :app:lintDebug :app:assembleDebug

# Keystore persistence: actual execution, not just APK compilation.
sh "$REPO/gradlew" :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sskaraoke.player.SessionStoreDeviceTest
```

Use `:app:assembleDebugAndroidTest` when only instrumentation compilation is
available, and explicitly report that device behavior was not tested.

## Node and browser selection

```sh
node --test "$REPO/tests/mobile-session.test.cjs"
node --test "$REPO/tests/workflows.test.cjs"

# Filter a known behavior; omitting --project retains phone AND tablet.
npm run test:browser -- --grep 'a horizontal swipe switches tabs'
# Inspect selection without running it; ensure it is not empty.
npm run test:browser -- --list --grep 'a horizontal swipe switches tabs'
```

For a single Node case, put `--test-name-pattern='title fragment'` before
the file argument. For Playwright, use titles from the owning test reference
and inspect skips as well as passes. Fixture and real-frontend modes have
different coverage; `--list` does not establish runtime success.

Default browser tests use the isolated fixture on loopback port 4178.
For hosted-client integration, set `KARAOKE_FRONTEND_BUILD` to an absolute
existing frontend build directory for the command, then unset it before
fixture-only checks. API responses are mocked and Socket.IO is not live.
Never treat the fixture run as proof of real drag-library integration.

## Broaden only when warranted

- Shared native/lifecycle changes: `:app:testDebugUnitTest` without filters.
- Build/dependency/release changes:
  `sh "$REPO/gradlew" :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`.
- Broad adapter changes or release checks: `npm test` and `npm run test:browser`.
- Metadata-only Skill documentation: check YAML name/description, local links,
  cited symbols/test selectors, scope and routing examples; use `git diff --check`.
  No application build/test or new documentation tool is required.

Real browser passkeys/server handoff, process death, hardware Keystore,
private-CA TLS, YouTube/fullscreen playback, install-over-existing-app and
publishing require appropriate device/server/repository checks. Report
unavailable prerequisites and skipped coverage; never substitute compilation
or mocks for these checks. Do not publish or delete releases just to validate docs.
