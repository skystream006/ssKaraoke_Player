# WebView adapter source map

| Concern | First source and anchors |
| --- | --- |
| Pure session/route/gesture helpers | [mobile-session.js](../../../app/src/main/assets/mobile-session.js): `readSession`, `restoreSession`, `safeLocation`, `swipeDirection`, `validUsername` |
| Snapshot/restoration and hosted storage | [mobile-shell.js](../../../app/src/main/assets/mobile-shell.js): `snapshot`, `scheduleSnapshot`, storage/history wrappers, `attach`/`restoreView` |
| Hosted auth integration | Same shell: `routeBrowserAuthentication`, `sessionResponse`, `expireSession`, fetch/XHR wrappers |
| Swipe/drag compatibility | Same shell: `excluded`, touch listeners, drag-state observation; pure `swipeDirection` |
| Web appearance | [mobile-shell.css](../../../app/src/main/assets/mobile-shell.css): Android-scoped selectors; shell `applyTheme` |
| WebView setup/navigation and transport | [MainActivity](../../../app/src/main/java/com/sskaraoke/player/MainActivity.kt): `showWebView`, `installDocumentScript`, `receiveMessage`, `navigate`, `disposeWebView` |
| Fullscreen/foreground playback | Same activity: custom-view callbacks, `hideFullscreen`, `updateKeepAwake`, WebView/media clauses in `onResume` and `onStop` |
| Isolated browser harness | [adapter.spec.cjs](../../../tests/browser/adapter.spec.cjs), [fixture.html](../../../tests/browser/fixture.html), [fixture-server.cjs](../../../tests/browser/fixture-server.cjs) |

`mobile-session.js` exports CommonJS for Node tests and a browser global for
injection; `mobile-shell.js` is a document-start IIFE, not a React component.
There is no local production React project to rebuild. The fixture emulates
selected hosted behavior and is not the production frontend.

Read [bridge](bridge.md) only for the affected boundary. For selectors or
touch behavior, begin at that listener/CSS selector and its test instead of
reading all authentication interception code.

## Adjacent owners and exclusions

Session schema, safe-route policy and expiry/logout meaning belong to
identity-session; native theme/pointer presentation belongs to native-ui.
The [common ownership table](../../repo-router/references/common.md#shared-contracts)
identifies exact shared surfaces. Keep PKCE/encryption internals unloaded
when their contract is unchanged. Ignore updater workflows and native form
helpers for web-only changes.

If the defect is in server permissions, queue persistence or the hosted
drag library itself, report the external dependency. Do not invent a
parallel local mutation path or silently modify a different repository.
