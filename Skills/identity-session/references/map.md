# Identity/session source map

| Behavior | Source and first symbols |
| --- | --- |
| Browser request, S256 challenge, callback validation | [BrowserAuth](../../../app/src/main/java/com/sskaraoke/player/BrowserAuth.kt): `PendingBrowserLogin`, `begin`, `loginUrl`, `acceptCallback`, `validatedOrigin` |
| Connectivity and authentication APIs | [SessionClient](../../../app/src/main/java/com/sskaraoke/player/SessionClient.kt): `ping`, `exchange`, `loginHostScreen`, `accountSession`, `resume`, `logout` |
| Coroutine HTTP cancellation/error privacy | Same file: `Call.awaitResponse`, `oneShotJsonBody`, `BrowserSignInException`, `HostScreenSignInException` |
| Stored model and JavaScript-facing state | [SavedSession](../../../app/src/main/java/com/sskaraoke/player/SavedSession.kt): `seed`, `toJson`, `withSnapshot`, `fromJson`, `signedOut`, `expired` |
| Keystore/atomic persistence | [SessionStore](../../../app/src/main/java/com/sskaraoke/player/SessionStore.kt): `key`, `read`, `update`, `flush`, `recoveryNeeded`, `writeFailed` |
| Server origin and safe invitation routes | [ServerAddress](../../../app/src/main/java/com/sskaraoke/player/ServerAddress.kt): `parse`, `owns`, `safeRoute`, `api` |
| Connection/login orchestration | [MainActivity](../../../app/src/main/java/com/sskaraoke/player/MainActivity.kt): `confirmServer`, `connect`, `showSignIn`, `beginHostScreenLogin`, `beginBrowserLogin`, `cancelSignIn`, `receiveBrowserCallback` |
| Account browser, invitation, logout transitions | Same activity: `openBrowserAccount`, `showBrowserAccount`, `promptJoinLink`, `receiveSharedLink`, `onNewIntent`, `signOut`, `clearSession` |
| Callback export, networking and backup policy | [Manifest](../../../app/src/main/AndroidManifest.xml), [network security](../../../app/src/main/res/xml/network_security_config.xml), [backup rules](../../../app/src/main/res/xml/backup_rules.xml), [data extraction rules](../../../app/src/main/res/xml/data_extraction_rules.xml) |

Read [contracts](contracts.md) by the affected section, not as an exhaustive
login tutorial. [README](../../../README.md) documents server prerequisites;
the authentication server is not implemented here.

## Cross-boundary decisions

- If changing a seed/snapshot field or safe route, consult
  [adapter map](../../webview-adapter/references/map.md) and both sides' tests.
- If changing layout only, use native-ui; do not pull cryptography or API
  exchange code into a control-spacing fix.
- `Call.awaitResponse` also serves the updater. Its lifetime/cancellation
  changes require app-delivery consultation, not a separate HTTP library Skill.
- Account authorization level and party membership role are different fields;
  native UI visibility is not the server's permission enforcement.

Keep updater implementation, release workflows, pointer internals and hosted
queue/playback implementation outside this investigation unless an actual
dependency emerges. Use symbol-scoped activity reads.
