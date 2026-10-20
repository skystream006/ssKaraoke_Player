# Shared architecture and ownership

Read only when a task crosses a boundary. Links are relative to this file;
resolve them to absolute paths in the current checkout before using tools.
Symbol names are discovery anchors; do not depend on historical line numbers.

## Application map

This is one Android `:app` module, not a local frontend/backend stack.
[Settings](../../../settings.gradle.kts) defines the module; the
[manifest](../../../app/src/main/AndroidManifest.xml) delivers launch,
shared-text invitations and custom-scheme returns to
[MainActivity](../../../app/src/main/java/com/sskaraoke/player/MainActivity.kt).

| Component | Implementation boundary | Domain |
| --- | --- | --- |
| Programmatic Android/Material views, settings, accessibility, pointer | `MainActivity` rendering; [PointerNavigation](../../../app/src/main/java/com/sskaraoke/player/PointerNavigation.kt); [resources](../../../app/src/main/res) | native-ui |
| Server/identity state and encrypted local checkpoint | [BrowserAuth](../../../app/src/main/java/com/sskaraoke/player/BrowserAuth.kt), [SessionClient](../../../app/src/main/java/com/sskaraoke/player/SessionClient.kt), [SavedSession](../../../app/src/main/java/com/sskaraoke/player/SavedSession.kt), [SessionStore](../../../app/src/main/java/com/sskaraoke/player/SessionStore.kt), [ServerAddress](../../../app/src/main/java/com/sskaraoke/player/ServerAddress.kt) | identity-session |
| Origin-restricted hosted-web integration | `MainActivity` WebView methods; [adapter assets](../../../app/src/main/assets) | webview-adapter |
| GitHub release consumption and Android installation | [UpdateClient](../../../app/src/main/java/com/sskaraoke/player/UpdateClient.kt), [UpdateSecurity](../../../app/src/main/java/com/sskaraoke/player/UpdateSecurity.kt); activity update methods | app-delivery |
| Build, signing, versioning and repository automation | [Gradle app configuration](../../../app/build.gradle.kts), [version baseline](../../../version.properties), [workflows](../../../.github/workflows) | app-delivery |

```text
Launch / invitation / browser return → MainActivity
  ├─ Native views and per-window pointer
  ├─ Identity/session → server authentication APIs + Android Keystore storage
  ├─ WebView adapter → hosted frontend → API / Socket.IO / YouTube IFrame
  └─ Updater → GitHub Releases → verified APK → Android installer
Manual release workflow → signed release asset consumed by updater
```

The server supplies the frontend and same-origin `/api`; its Socket.IO URL
must be phone-reachable. Browser authentication/account management uses
Custom Tabs and the website's HTTPS origin. There is no bundled server,
application database, native audio engine, or background playback service.
See [README](../../../README.md), especially Connect, Saved Sign-In and
Karaoke and Gestures, only when their external contracts are relevant.

## MainActivity ownership by symbol

| Owner | Start here, not at the entire file |
| --- | --- |
| native-ui | `buildChrome`, `applyInsets`, rendering portions of `showServerSetup`/sign-in/error screens, `showSettings`, `formPage`, `button`, `inputField`, `themeDialog`, `applyNativeTheme`, `tintView` |
| identity-session | `confirmServer`, `connect`, `showSignIn` connectivity gating, `beginHostScreenLogin`, `beginBrowserLogin`, `cancelSignIn`, `receiveBrowserCallback`, account-browser handoff, invitation handling, `signOut`, `clearSession` |
| webview-adapter | `showWebView`, `installDocumentScript`, `receiveMessage` transport, `navigate`, `refreshPage`, `disposeWebView`, `hideFullscreen`, `updateKeepAwake` |
| app-delivery | `checkForUpdates`, `confirmDownload`, `downloadUpdate`, `offerInstall`, `cancelUpdate` |

For `onCreate`, `onNewIntent`, `onResume`, `onStop`, `onPause`, and `onDestroy`,
inspect only the affected clauses and referenced state. Rendering a sign-in
button is native-ui; changing its authentication transition is identity-session.
Authentication message semantics remain identity-owned even inside the bridge.

## Shared contracts

| Contract and authoritative owner | Consumer and expansion rule |
| --- | --- |
| `SavedSession.seed`, `withSnapshot`, serialization: identity-session | Web adapter consumes schema; native UI consumes preferences. Expand when fields/accepted values change. |
| `ServerAddress.safeRoute`: identity-session policy | `mobile-session.js.safeLocation`: adapter implementation. Route/invitation changes require parity and both suites. |
| [color-themes.json](../../../app/src/main/assets/color-themes.json): native-ui | `SavedSession.themes` validates keys; web adapter applies palette. Key changes need identity-session; web behavior needs webview-adapter. |
| `Call.awaitResponse` in `SessionClient`: identity-session | Also used by `UpdateClient`; cancellation/response-lifetime changes require updater checks. |
| Origin/main-frame messaging and document-start injection: webview-adapter | Identity-session owns seed privacy and auth transitions; native-ui owns presentation/input. |
| Asset naming, version and signing identity: app-delivery | Release producer and updater consumer must agree; do not change either in isolation. |

## Normally outside loaded context

- The external `ss_karaoke_party` frontend/backend: server permissions, party
  mutations, queue persistence and playback. Browser fixtures are simulations,
  not implementations to modify in place of server features.
- Generated build outputs, Gradle/Kotlin caches, dependencies, browser reports,
  APKs, IDE/local configuration and signing secrets. Use [gitignore](../../../.gitignore)
  if investigating generated-file boundaries.
- Binary icon/font contents and licenses unless explicitly changing assets.
  Preserve the checked-in development signing key; do not regenerate it.
- Unrelated domain tests, whole-file activity reads and full README loads.

There is no separate API, persistence, testing, shared-library, observability,
or in-app-jobs Skill. Session persistence stays with identity; feature tests
stay with their owner; scheduled release cleanup belongs to app-delivery.
