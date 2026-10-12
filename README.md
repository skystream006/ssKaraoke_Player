# ssKaraoke Player for Android

An Android client for [ss_karaoke_party](https://github.com/skystream006/ss_karaoke_party), starting at **v0.01.00**. Supports Android 8.0 (API 26) and later; targets Android 16 (API 36).

This is a **hybrid Android app**: the selected server's actual karaoke frontend runs in an origin-restricted WebView. Native Kotlin screens provide server setup, encrypted persistence, app settings, and APK updates. Keeping the server frontend preserves its current features and server permissions without maintaining a divergent copy or inventing replacement endpoints. Authentication follows the server's [passkey update](https://github.com/skystream006/ss_karaoke_party/commit/13d7f33fd11c6225134ed1cd8bf39b452059ab97); WebView passkeys also require the website association described below.

## Connect

1. Install the APK and enter the karaoke **web frontend's configured HTTPS hostname**, for example `https://karaoke.example.com`. A bare hostname defaults to HTTPS. You can also enter a full host invitation link (`/join/invite#invite=…`).
2. Sign in with a passkey, or create an account with a passkey using the hosted login screen. The server controls account approval. A current host invitation instead grants a restricted guest session, not member/admin access.
3. Create or join a party. Reopening restores the selected server, login, username, party/member, tab, and scroll position.

The frontend must expose `/api` on the **same origin**. Its existing Socket.IO URL must also be reachable from the phone. Use the real phone-reachable hostname/IP, not the computer's `localhost`. Subdirectory deployments and separate reverse-proxy login walls are not supported. The app does not bundle or replace the frontend or backend.

HTTP requires explicit confirmation because session tokens and traffic are unencrypted on the network; ordinary HTTP LAN addresses cannot use passkeys. HTTPS hostname and certificate checks are never bypassed. Android-installed private CA certificates are supported for self-hosted servers. External main-frame links require confirmation and open in the system browser; their pages cannot access the native bridge.

Keep Android System WebView/Chrome updated. The app requires WebView's origin-scoped messaging and document-start scripting; older providers show an update requirement instead of insecurely injecting saved credentials after page load.

### Passkey setup

**Server compatibility prerequisite:** the referenced `13d7f33` server commit is not sufficient for Android WebView passkey verification. App-scoped WebAuthn signs an `android:apk-key-hash:…` origin, while that server verifies only the website origin. The server must explicitly trust the installed APK's signing-certificate origin for registration, login, and additional-passkey verification, alongside its configured website origin/RP ID. Its current `PASSKEY_ORIGIN`/secondary settings accept only web origins, so this needs a server code change, not just another environment variable. Keep challenge, RP ID, signature, and user-verification checks intact and allowlist only trusted distribution certificates. **Digital Asset Links alone will not fix this mismatch.** This repository adds the Android integration and session persistence; end-to-end passkey sign-in remains blocked until the server supports Android origins.

Passkeys use WebView's **app-scoped Web Authentication** support and Android Credential Manager, not a custom JavaScript credential bridge. Use Android 9 or later, an updated WebView, and a passkey-capable credential provider (such as Google Password Manager). Unsupported devices can still use host invitation sessions. Cancelling a passkey prompt or awaiting account approval does not create a saved login.

The server must use its configured WebAuthn HTTPS origin/RP hostname. Associate that website with the installed APK by serving `https://YOUR_HOST/.well-known/assetlinks.json` as public JSON without redirects:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.sskaraoke.player",
    "sha256_cert_fingerprints": ["YOUR_APK_SIGNING_CERTIFICATE_SHA256"]
  }
}]
```

Replace the fingerprint with the SHA-256 certificate fingerprint from `keytool -list -v -keystore YOUR_KEYSTORE -alias YOUR_ALIAS`, matching the installed build. Use a private distribution signing key for real accounts: the repository's public debug key is not a secure identity to authorize for credential sharing. Changing the hostname/RP ID or signing key may require new passkeys/association configuration. See [Android's WebView authentication setup](https://developer.android.com/identity/sign-in/credential-manager-webview). No browser-privileged mode, TLS bypass, or automatic external-browser token transfer is used.

## Karaoke and Gestures

The hosted server UI retains party creation/joining, returning members, QR links, search and YouTube URL entry, karaoke filtering, queue ordering/removal/status/reset, playback and seek controls, auto-advance, key/tempo/vocal controls, queue locking, member management, reactivation, duplication, and administration wherever the server offers them. The server remains authoritative for every permission and mutation.

- Swipe horizontally in the guest content area to move between Search, Queue, and Status. Swipe within the organizer sidebar to move between Playlist, Search, and its audio settings.
- Long-press a song's enlarged drag handle, then drag and drop to reorder. This uses the server client's existing `@hello-pangea/dnd` touch sensor and reorder endpoint, with native haptic feedback. Queue locks and disabled drag permissions are respected.
- Sliders, inputs, buttons, video, and active song drags do not trigger tab swipes. Existing play/remove controls remain available.
- Android Back navigates back; full-screen video exits before the page does. The organizer screen stays awake while visible.
- With a remote or keyboard, use the directional keys to reveal and move the pointer; hold a direction for smooth, accelerating movement. **OK/Enter** clicks, and holding it while moving drags sliders, scrolls content, or long-presses song handles. The pointer works on the party, native screens, dialogs, and full-screen video. **Menu** toggles between pointer and ordinary focus/keyboard navigation for the current window. Touch/mouse input hides it; the on-screen keyboard and screen-reader touch exploration retain their normal controls.
- Share a current host invitation link to ssKaraoke Player from Android, or use **App settings > Open party link** for the selected server. Keep the `#invite=…` fragment intact; old static party codes no longer grant access.

YouTube playback stays in the original IFrame player. Key/tempo/vocal behavior has the same limitations as the server frontend; this app does not add pitch shifting, audio source separation, offline media, or a background playback service. Leaving the foreground pauses local video. Reopening loads the live party state, not a stale saved queue or a forced historical playback position. Unsubmitted forms/search text are not checkpointed.

## Saved Sign-In

The server issues a 24-hour bearer session after successful passkey registration/login verification (`/api/auth/register/verify` or `/api/auth/login/verify`). Invitation acceptance at `/api/parties/:id/join` issues a guest token, bounded by the host session/invitation lifetime. Its web client keeps authentication in `sessionStorage` and the account's default name in `localStorage`.

The Android adapter saves the session when the hosted client stores its token/access level, including before page/tab restoration finishes. The token, username, and location are encrypted with AES-256-GCM using an Android Keystore key, written atomically in private no-backup storage, and excluded from cloud/device-transfer backups. The token is restored into only the selected origin's web session before React initializes. Passkey private keys stay with the credential provider; the app never captures passwords or passkey material. Old saved passwords and username-selection flags are discarded when loading an earlier app's session.

On startup/refresh, the native client checks the token at `GET /api/auth/me`, which supports both account and guest sessions. The hosted client also uses `/auth/me` to restore the authoritative account/party scope. A 401 or the hosted client's session-expiry event removes the invalid login; use your passkey or a new invitation to sign in again. The app does not silently renew expired sessions or call the removed password/QR-session endpoints. Offline/5xx verification failures preserve the native login and offer retry. Failed mutations are never automatically replayed.

**Sign out**, **Switch user**, and **Change server** first revoke the token with `POST /api/auth/logout`, then remove the saved login/location and clear this app's WebView storage/cookies. Revoking a host session invalidates its invitations/guest access. A network/server failure preserves the saved session and asks you to retry; an already-expired token can be cleared. Uninstalling removes local state but does not itself revoke a server session or delete provider-managed passkeys. The application does not persist Android location/GPS data.

## App Settings and Updates

The native **App settings** gear floats at the bottom right at 50% opacity, including before login, and hides during full-screen video. There is no native top toolbar, and the hosted frontend's user top bar is hidden inside the Android app. Its username and **Switch user** action are available under **App settings > Server and sign-in**. Party headers and their controls remain visible. App settings is separate from server administration.

- **Check for updates** is a filled, full-width Material button. The app also checks once when launched in the foreground.
- **Download and install** asks before downloading, shows progress, and supports cancellation. Leaving the foreground cancels an unfinished transfer; it is not retried automatically.
- **Color theme** shows the server's six colors in a horizontal, scrollable swatch row with a selection ring and screen-reader labels, without visible names. Selection is remembered on the device and immediately updates both native screens and the party without reloading it. Native and web colors share the bundled `color-themes.json` palette. The web header's duplicate theme picker is hidden only inside the Android app.
- **Server and sign-in** includes the current address, identity, **Home**, **Refresh**, invitation-link entry, browser access, server switching, sign-out, and server administration for admin sessions. Account sessions also have **Account and passkeys**, opening the hosted `/account` page for passkey management and account controls. **Home** closes settings without signing out; guest sessions remain restricted to their party by the server frontend.

**App settings > Server and sign-in > Switch user** confirms sign-out and revokes the current session before returning to login. Sign in with the other account's passkey or open a current host invitation. It no longer changes an alias while retaining another account's credentials; existing party members and queued songs are not renamed or deleted.

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

The manual release workflow uploads a release APK artifact. Extract an Actions artifact ZIP before installing its APK; the in-app updater reads Releases, not Actions artifacts.

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

Native JVM tests cover URL/invitation boundaries, saved-session serialization and legacy migration, account/guest session validation, revocable logout, HTTP errors/redirects, release parsing, download integrity, signing identity, frame-rate-independent pointer movement and input handling, and the native settings/setup layouts at narrow width and 150% text size. Android release lint and release APK assembly are included in the manual release workflow.

Node 22 LTS is recommended for browser/workflow tests:

```powershell
npm ci
npx playwright install chromium
npm test
npm run test:browser
```

The standalone browser fixture verifies account/guest session and tab restoration, hosted authentication storage updates, sign-out/expiry and transient verification failures without mutation replay, gesture exclusions, and live theme switching. To also exercise the **actual server frontend** and its touch drag library against isolated mock API responses:

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

Before distributing, verify passkey registration/login/cancellation and additional passkey management against the configured HTTPS hostname/Digital Asset Links on a real device, plus host invitation login, process death/reopen, expiry/revocation, private-CA networking, YouTube playback/fullscreen/seek, background/resume, queue changes shared with another device, and install-over-existing-app. Check invitation access on Android 8 and passkeys on supported Android versions. Live passkey/provider prompts, server playback, hardware Keystore behavior, Android's installer, and publishing require device/repository checks; mocked browser tests cannot establish them.

## Assets

The launcher icon is the [user-specified image](https://github.com/skystream006/ss_karaoke-apk/blob/main/app/src/main/res/drawable/app_icon.png), bundled unchanged. Native screens bundle [Manrope](https://github.com/google/fonts/tree/main/ofl/manrope), with its [SIL Open Font License](app/src/main/assets/font-licenses/Manrope-OFL.txt). The server frontend retains its own styling and assets.