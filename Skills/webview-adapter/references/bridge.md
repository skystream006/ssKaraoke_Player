# Bridge and hosted-client contracts

Source anchors are in [map](map.md). Identity field semantics and native-only
secret rules are authoritative in
[identity contracts](../../identity-session/references/contracts.md).

## Native setup and injection

- `showWebView` requires document-start scripting and origin-scoped web
  messaging. Unsupported providers show an update requirement, not an
  insecure post-load credential injection fallback.
- `KaraokeHost` accepts messages only from the current WebView, main frame
  and selected origin, with a bounded message length. External main-frame
  navigation is blocked or user-confirmed in the system browser.
- Keep file/content access disabled, mixed content prohibited, normal TLS
  validation and WebView permission denial. Do not add a wildcard bridge.
- `installDocumentScript` replaces the old script handler and injects, in
  order: `__karaokeSeed`, `__karaokeThemes`, `__karaokeCss`, session helpers,
  then the shell. Both native injection and shell execution guard the top frame.
- The shell consumes/deletes injected globals and restores storage before
  the hosted app initializes. The seed contains a session token, but never
  the native HOSTSCREEN key or pending PKCE verifier/state.

## Messages and ownership

| Web → native message | Native consumer |
| --- | --- |
| `snapshot` with `state` | `SavedSession.withSnapshot`, preserving the current native-selected theme |
| `unavailable` | Retry UI preserving saved identity |
| `expired` | Identity expiry transition; may retain a HOSTSCREEN key for renewal |
| `logout` | Explicit signed-out transition; no automatic HOSTSCREEN renewal |
| `ready` | Apply the current native theme to the loaded document |
| `haptic` | Native feedback for tab swipe/drag start |
| `signin`, `account` | Native/browser sign-in or account-management UI |

Native calls `window.KaraokeAndroid.snapshot()` and `applyTheme(key)`.
`KaraokeAndroid` is the shell API; `KaraokeHost` is the native message endpoint.
Do not add credential-bearing account-management links.

## Storage and authentication timing

- Hosted token/level/member state lives in `sessionStorage`; default username
  and theme live in `localStorage`. Helper validation is paired with the native
  model and route policy; update both sides when their contract changes.
- `authLevel` and `defaultUsername` updates checkpoint synchronously, even
  during view restoration; other changes may debounce. Do not delay newly
  issued identity until the selected tab finishes restoring.
- Fetch and XHR wrappers observe same-origin `/api` responses without replaying
  requests. `/auth/me` 401 or `auth:expired` means expiry; a generic mutation
  401 does not. Outages suspend snapshots so hosted storage clearing cannot
  erase the durable login.
- Track the request's token to ignore stale responses. `logoutToken` distinguishes
  explicit logout from concurrent expiry; successful/already-expired logout
  reports `logout`, while failure permits later expiry handling.
- The storage wrapper also detects explicit token removal. The adapter does
  not itself implement the server's logout API; preserve the hosted client's
  revoke-before-clear contract and the tested races.

## View, appearance and lifecycle

- Restore only safe route/tab/scroll state, not queues, form text or playback
  positions. History navigation ends old view restoration; current server
  state remains authoritative.
- Apply themes in place from the native-supplied palette, accepting own keys
  only. CSS stays scoped under `html[data-sskaraoke-android]`; hide only the
  intended hosted toolbar/picker, not party controls.
- Swipe exclusions include interactive controls, sliders, media, drag handles
  and active dragging. Hosted `@hello-pangea/dnd` owns touch reordering,
  persistence and permission checks; do not rearrange React-owned rows.
- Browser tests verify actual reorder integration only with an external
  frontend build. Do not infer a new endpoint payload schema from the fixture.
- Fullscreen uses Android custom views; Back exits fullscreen first. Organizer
  visibility controls wakefulness. On backgrounding, checkpoint, pause hosted
  YouTube media/timers and preserve normal foreground restoration.
