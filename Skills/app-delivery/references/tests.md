# Delivery test selection

Use [shared commands](../../repo-router/references/validation.md). Keep runtime
and build/release tracks separate unless their contract changes.

## Runtime

Source: [UpdateTest.kt](../../../app/src/test/java/com/sskaraoke/player/UpdateTest.kt).
Run `com.sskaraoke.player.UpdateTest`, or start with these method anchors:

| Change | Existing tests |
| --- | --- |
| Version/asset selection | `paddedVersionsCompareNumerically`, `releaseMustContainTheExactStableUniversalApk` |
| Package/version/signers | `installsRequireMatchingPackageNewerCodeAndIdenticalSigners` |
| Transfer/integrity | `downloadCompletesOnlyAfterSizeAndDigestVerification`, `truncatedDownloadsAreRemoved`, `invalidChecksumsAreRejected` |
| Network policy/API response | `latestReadsAnOrdinaryReleaseResponseWithoutExpectingPadding`, `noPublishedReleaseIsNotAnError`, `downloadNeverFollowsAnHttpOrUnrelatedHostRedirect` |

Presentation changes add the affected [native UI tests](../../native-ui/references/tests.md).
Shared HTTP helper changes add session cancellation/transport tests.
Run lint/debug assembly for native code. Mocked updater tests do not validate
real APK parsing, unknown-source permission transitions or install-over-existing;
exercise those on a device when affected.

## Build and workflows

[workflows.test.cjs](../../../tests/workflows.test.cjs) verifies SDK package
setup, manual/full-history release behavior, signing verification/non-overwrite,
PR-reminder isolation and inclusion of native/browser/lint/build steps.
Run that file for its covered workflows; `npm test` also includes session
helper checks.

There is **no existing cleanup-workflow behavior test**. For cleanup changes,
inspect the specific retention/tag cases and report the gap; release/reminder
test success is not cleanup coverage. Never run destructive cleanup as a test.

For dependency/toolchain changes, broaden to native unit tests, release lint
and release assembly; include Node/browser suites if affected. Compile device
tests when changing instrumentation dependencies. Changing release filenames
requires updater tests plus producer assertions/metadata checks, not just a
successful APK build.
