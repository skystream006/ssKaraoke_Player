# ssKaraoke Player for Android

An Android client for [ss_karaoke_party](https://github.com/skystream006/ss_karaoke_party), starting at **v0.01.00**. Supports Android 8.0 (API 26) and later; targets Android 16 (API 36).

This is a **hybrid Android app**: the selected server's actual karaoke frontend runs in an origin-restricted WebView. Native Kotlin screens provide server setup, encrypted persistence, app settings, and APK updates. Keeping the server frontend preserves its current features and server permissions without maintaining a divergent copy or inventing replacement endpoints. No changes to the karaoke server are required.

## Connect

1. Install the APK and enter the karaoke **web frontend** address, for example `https://karaoke.example.com` or `http://192.168.1.50:3000`. A bare hostname defaults to HTTPS. You can also enter a full `/join/CODE` link.
2. Sign in with the existing member or admin password and select a default username using the server's existing autocomplete. QR/join-link access retains the server's password-free member flow.
3. Create or join a party. Reopening restores the selected server, login, username, party/member, tab, and scroll position.

The frontend must expose `/api` on the **same origin**. Its existing Socket.IO URL must also be reachable from the phone. Use the real phone-reachable hostname/IP, not the computer's `localhost`. Subdirectory deployments and separate reverse-proxy login walls are not supported. The app does not bundle or replace the frontend or backend.

HTTP requires explicit confirmation because passwords and traffic are unencrypted on the network. HTTPS hostname and certificate checks are never bypassed. Android-installed private CA certificates are supported for self-hosted servers. External main-frame links require confirmation and open in the system browser; their pages cannot access the native bridge.

Keep Android System WebView/Chrome updated. The app requires WebView's origin-scoped messaging and document-start scripting; older providers show an update requirement instead of insecurely injecting saved credentials after page load.

## Karaoke and Gestures

The hosted server UI retains party creation/joining, returning members, QR links, search and YouTube URL entry, karaoke filtering, queue ordering/removal/status/reset, playback and seek controls, auto-advance, key/tempo/vocal controls, queue locking, member management, reactivation, duplication, and administration wherever the server offers them. The server remains authoritative for every permission and mutation.

- Swipe horizontally in the guest content area to move between Search, Queue, and Status. Swipe within the organizer sidebar to move between Playlist, Search, and its audio settings.
- Long-press a song's enlarged drag handle, then drag and drop to reorder. This uses the server client's existing `@hello-pangea/dnd` touch sensor and reorder endpoint, with native haptic feedback. Queue locks and disabled drag permissions are respected.
- Sliders, inputs, buttons, video, and active song drags do not trigger tab swipes. Existing play/remove controls remain available.
- Android Back and the native back arrow navigate back; full-screen video exits before the page does. The organizer screen stays awake while visible.
- Share a party link to ssKaraoke Player from Android, or use **App settings > Open party link** to enter a code/link for the selected server.

YouTube playback stays in the original IFrame player. Key/tempo/vocal behavior has the same limitations as the server frontend; this app does not add pitch shifting, audio source separation, offline media, or a background playback service. Leaving the foreground pauses local video. Reopening loads the live party state, not a stale saved queue or a forced historical playback position. Unsubmitted forms/search text are not checkpointed.

## Saved Sign-In

The server issues a 24-hour bearer token from `POST /api/auth`; QR sessions come from `GET /api/auth/qr-session`. Its web client normally keeps authentication in `sessionStorage` and the default username in `localStorage`.

The Android adapter captures a password only after successful authentication. The native saved session, password, username, and location are encrypted with AES-256-GCM using an Android Keystore key, written atomically in private no-backup storage, and excluded from cloud/device-transfer backups. The saved password is **never injected back into JavaScript**. The bearer token is restored into the selected origin's web session before React initializes, as required by the existing web client.

On startup, the native client checks the token at `GET /api/parties`. A 401 renews it using the saved password, or the QR endpoint for a QR session. A running page also reports API 401s so the app can renew and reload the same location. Failed mutations are never automatically replayed; retry the last action after renewal. Offline/5xx failures preserve credentials. A changed/rejected password requires sign-in again.

**Sign out** or **Change server** removes the saved credentials and location and clears this app's WebView storage/cookies. There is no server token-revocation endpoint. Uninstalling the app removes local state. The application does not persist Android location/GPS data.

## App Settings and Updates

The native **App settings** gear is always available, including before login. It is separate from server administration.

- **Check for updates** is a filled, full-width Material button. The app also checks once when launched in the foreground.
- **Download and install** asks before downloading, shows progress, and supports cancellation. Leaving the foreground cancels an unfinished transfer; it is not retried automatically.
- **Color theme** contains the server's six labeled color swatches: Neon Purple, Ocean, Forest, Sunset, Midnight Gold, and Dark. Theme selection is remembered on the device and applied without reloading the party. The web header's duplicate theme picker is hidden only inside the Android app.
- **Server and sign-in** includes the current address, identity, join-link entry, browser access, server switching, sign-out, and server administration for admin sessions.

**App settings > Server and sign-in > Switch user** opens the server's username selector with its existing name suggestions, without asking for the password again. Your login, access level, and saved password are retained. The previous party-member identity is cleared and you return to the home screen to join as the selected user; existing members and queued songs are not renamed or deleted. Both an unfinished username selection and the newly chosen name survive reopening the app.

Updates are read from [this repository's latest GitHub release](https://github.com/skystream006/ssKaraoke_Player/releases/latest), without karaoke login. The updater selects `ssKaraoke-Player-v<version>.apk`, permits only approved HTTPS GitHub download hosts, bounds download size, verifies size and GitHub's SHA-256 asset digest when supplied, and checks the package, newer version code, release version name, and exact signing certificate set. Android verifies the APK again and asks for final installation approval.

If requested, enable **Install unknown apps** for ssKaraoke Player, return to the app, and confirm installation. A compatible update preserves login and settings. If Android kills the app during download or permission handling, check and download again; the app never automatically trusts an old partial file. Device policy or Samsung Auto Blocker may prohibit sideloading.

The version shown in native settings is the Android app version. The server frontend may display its own separate version watermark.

## Build

Use Java 17, Android SDK Platform 36, and a **full Git checkout**. Configure `JAVA_HOME` and `ANDROID_HOME`, or open the project in Android Studio with a Java 17 Gradle JDK. The checked-in Gradle 8.13 wrapper and distribution are checksum verified.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

On macOS/Linux use `sh ./gradlew` with the same tasks. Installable outputs are `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release.apk`. With a connected device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is **`com.sskaraoke.player`**. Keep it and the signing certificate unchanged across updates. This is a new application, not a package-compatible upgrade of the older `ss_karaoke-apk` app.

The only requested permissions are internet access and requesting APK installation. No microphone, camera, contacts, GPS, or broad storage permission is needed.

## Versioning and Releases

Versions use ssMusic_Player's first-parent Git-history approach. No bot writes version-bump commits:

- The first application revision is **v0.01.00**. [version.properties](version.properties) fixes the baseline at the first app commit after this repository's initial README commit.
- Each subsequent first-parent commit advances the patch: `v0.01.01`, `v0.01.02`, and so on. Normal merge/squash PRs advance once; rebase merges/direct commits may advance further, like the reference repository.
- Rebuilding the same commit retains the same version. Android `versionCode` advances with first-parent history. Shallow checkouts fail with instructions to fetch the complete history.
- Closing an unmerged PR does not advance main's version or post a release reminder. Releases are never published automatically.

After pushing this project, use **Actions > Manual Android Release > Run workflow** on **main**. The workflow checks out latest main with full history, runs browser/native tests and release lint, builds and verifies a signed universal APK, and publishes the versioned APK and SHA-256 file as the latest release. Re-running a published version leaves it unchanged. The post-merge reminder links to that workflow without checking out or executing PR code.

[Android checks](.github/workflows/android.yml) also runs on PRs and main pushes and uploads a debug APK artifact. Extract an Actions artifact ZIP before installing its APK; the in-app updater reads Releases, not Actions artifacts.

### Stable Signing Key

[app/debug.keystore](app/debug.keystore) is deliberately included, using alias `androiddebugkey` and the standard public debug password `android`. Debug builds, local release builds, and manual releases all use this same key by default, so they can update one another. **Keep this file permanently; regenerating it breaks updates.**

This is a public development key, **not a secure production identity**: anyone with the repository can sign an APK with it. Distribute only trusted builds. For production, configure all four optional repository Actions secrets before the first distributed release:

| Secret | Value |
| --- | --- |
| `APK_SIGNING_KEYSTORE_BASE64` | Base64-encoded permanent distribution keystore |
| `APK_SIGNING_STORE_PASSWORD` | Keystore password |
| `APK_SIGNING_KEY_ALIAS` | Signing alias |
| `APK_SIGNING_KEY_PASSWORD` | Key password |

All four must be present together or all absent. For a local distribution build, set `APK_SIGNING_STORE_FILE` and the three password/alias environment variables. Never commit production signing keys or their secrets. Back up the permanent key securely.

Switching signing keys after distribution prevents in-place updates. A version bump cannot fix a signer mismatch; do not uninstall merely to bypass it, because that deletes saved state.

## Verification

Native JVM tests cover URL boundaries, saved-session serialization, password/QR renewal, HTTP errors/redirects, release parsing, download integrity, signing identity, and the native settings/setup layouts at narrow width and 150% text size. Android lint and both APK build variants are included in CI.

Node 22 LTS is recommended for browser/workflow tests:

```powershell
npm ci
npx playwright install chromium
npm test
npm run test:browser
```

The standalone browser fixture verifies session/username/tab restoration, successful-login capture for fetch and Axios/XHR, 401 notification without mutation replay, gesture exclusions, and live theme switching. To also exercise the **actual server frontend** and its touch drag library against isolated mock API responses:

```powershell
$env:KARAOKE_FRONTEND_BUILD = 'C:\DATA\ss_karaoke_party\frontend\build'
npm run test:browser
Remove-Item Env:KARAOKE_FRONTEND_BUILD
```

The build directory must contain the built server frontend. No server data is changed by these tests. Real-client screenshots are written under `test-results/server`; standalone screenshots under `test-results/fixture`. The real-client suite was exercised on phone and tablet viewports, including a touch long-press/drop that calls the reorder endpoint.

The device-only Keystore persistence test is compiled into the instrumentation APK:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

No physical device/emulator was connected during initial implementation. Before distributing, verify a real password and QR login, process death/reopen, private-CA networking, YouTube playback/fullscreen/seek, background/resume, queue changes shared with another device, and install-over-existing-app on Android 8 and current Android versions. Live server playback, hardware Keystore behavior, Android's installer, and the GitHub Actions publishing workflow require those device/repository checks; mocked browser tests cannot establish them.

## Assets

The launcher icon is the [user-specified image](https://github.com/skystream006/ss_karaoke-apk/blob/main/app/src/main/res/drawable/app_icon.png), bundled unchanged. Native screens bundle [Manrope](https://github.com/google/fonts/tree/main/ofl/manrope), with its [SIL Open Font License](app/src/main/assets/font-licenses/Manrope-OFL.txt). The server frontend retains its own styling and assets.