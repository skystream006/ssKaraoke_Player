# Updater and installer

## Entry points

| Concern | Source and symbols |
| --- | --- |
| Latest release, bounded HTTP response, cancellation | [UpdateClient](../../../app/src/main/java/com/sskaraoke/player/UpdateClient.kt): `latest`, `cancel` |
| Transfer, redirect validation, partial-file cleanup | Same file: `download`, `downloadResponse` |
| Version and release asset contract | [UpdateSecurity](../../../app/src/main/java/com/sskaraoke/player/UpdateSecurity.kt): `AppVersion`, `ReleaseInfo.parse`, `ReleaseInfo.fileName` |
| Integrity/package/version/signer checks | Same file: `verifyFile`, `verifyApk`, `validateUpgrade`, `identity` |
| User prompts, progress, permission return, lifecycle | [MainActivity](../../../app/src/main/java/com/sskaraoke/player/MainActivity.kt): `checkForUpdates`, `confirmDownload`, `downloadUpdate`, `offerInstall`, `cancelUpdate`; update clauses in `onResume`/`onStop` |
| Installer file exposure | [Manifest](../../../app/src/main/AndroidManifest.xml): install permission and FileProvider; [update_paths.xml](../../../app/src/main/res/xml/update_paths.xml) |

For update-button sizing, use native-ui instead of opening networking code.
For changes to the producer/consumer artifact contract, also read
[build-release](build-release.md).

## Invariants

- Latest stable release comes from `BuildConfig.UPDATE_REPOSITORY` using
  GitHub's API without karaoke credentials. A 404 means no release;
  rate limits are not authentication expiry.
- `ReleaseInfo.parse` rejects drafts/prereleases and requires exactly one
  `ssKaraoke-Player-v<version>.apk` asset at the exact repository/tag URL.
  Versions compare numerically; padded display versions are not lexical order.
- Download size is bounded to the declared asset size and the 200 MiB limit.
  GitHub's SHA-256 asset digest is checked when supplied; it is not always present.
- Redirects are handled explicitly with a finite limit and only approved
  HTTPS GitHub download hosts. Do not loosen host validation for convenience.
- Downloads use a partial file, enforce declared/received size, verify content
  and remove partial files on failure/cancellation. Never trust a stale
  download merely because it exists.
- APK package must match the installed application, version code must increase,
  version name must match the release and the nonempty signer set must match
  exactly. Android performs its own installation checks too.
- `offerInstall` obtains user approval, handles unknown-source permission
  and revalidates the APK before sharing a cache-scoped FileProvider URI.
- Foreground startup checks once; leaving the foreground cancels unfinished
  transfers. Do not introduce automatic retry or background installation.

`Call.awaitResponse` is shared with session networking; consult
[shared ownership](../../repo-router/references/common.md#shared-contracts)
if changing its cancellation or response-lifetime behavior. Ignore karaoke
session storage, adapter DOM logic and unrelated release workflows otherwise.
