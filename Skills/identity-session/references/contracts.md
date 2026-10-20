# Identity contracts

Sources are indexed in [map](map.md). Read only the affected contract.

## Server addresses and connectivity

- `ServerAddress` normalizes an origin, rejects embedded credentials and
  unsupported deployment paths, and compares scheme/host/port for ownership.
- `safeRoute` strips unrelated query/fragment data and allows only known
  routes. Only the canonical `/join/invite#invite=…` shape retains its
  43-character base64url invitation fragment, without a query.
- Native route policy and JavaScript `safeLocation` must remain aligned.
  Invitations are restricted guest access, not browser account login.
- `SessionClient.ping` is an unauthenticated GET to the server root with
  a 20-second call timeout, normal TLS validation, no redirects and 2xx-only
  success. Connection-route fallback is allowed; HTTP-status retry is not.
  Its network interceptor rejects errors before OkHttp retries 408/503.
- HTTP requires native confirmation; never bypass TLS/hostname checks.
  The network configuration trusts Android system and user-installed CAs.

## Browser sign-in and account management

- `BrowserAuth.begin` creates independent 32-byte state and verifier values.
  `/app-login` receives S256 challenge, state and the fixed redirect URI:
  `com.sskaraoke.player:/oauth/callback`.
- Pending login is origin-bound, encrypted and valid for ten minutes.
  Validate exact URI scheme/authority/raw path/parameters, state and age.
- `MainActivity.receiveBrowserCallback` validates the current pending request,
  durably consumes it before exchange, and guards against stale asynchronous
  completion using origin/state-generation checks. Cancellation, duplicates,
  server switching and interruption must not reuse an exchange.
- `POST /api/auth/app/token` submits code, verifier and redirect URI exactly
  once. Redirects, authentication follow-ups and replay must not occur.
  Require a bounded flat session response with a valid token/name, approved
  member/admin level and future expiry.
- Errors must not expose code, verifier, token or raw transport failures.
  `BrowserSignInException` fixes a null cause and returns null from
  `CopyableThrowable.createCopy` to prevent coroutine recovery adding a cause.
- Passkeys and account management use Custom Tabs and website HTTPS origins.
  This flow does not use WebView APP-mode passkeys or Digital Asset Links.
  Opening `/account` does not transfer the native app token.
- The server must implement and secure the handoff. Native mocks cannot prove
  server approval, one-time-code consumption or live WebAuthn behavior.

## HOSTSCREEN

- `POST /api/auth/hostscreen/login` sends `{key}` directly, not PKCE.
  Preserve the exact accepted key. A successful session has level `hostscreen`
  and starts at `/join`; organizer membership is a separate role.
- Save the key only after successful login, in encrypted native state.
  First-time failure/interruption must not persist the entered key.
- `resume` verifies existing tokens first. A 401 or an expired checkpoint may
  trigger one fresh login with the saved key; transient verification failures
  preserve identity without submitting the key.
- Rejected credentials clear the key; temporary failures preserve it and
  offer explicit retry without looping. Explicit logout/switching user or
  server removes it. Older token-only installs require a manual key login.

## Persistence and WebView contract

- `SessionStore` uses an Android Keystore AES-256-GCM key and `AtomicFile`
  in private no-backup storage. Updates use a single writer; critical pending
  login transitions require durable `flush` and handling of `writeFailed`.
- `SavedSession.seed()` includes the session token, authentication level,
  username, member identity/role, safe route, theme and view state. It is
  **not credential-free**; exposure is restricted to the selected WebView origin.
- Only native `toJson()` adds origin, pending PKCE request and HOSTSCREEN key.
  Never expose pending verifier/state or the HOSTSCREEN key in JavaScript.
- `withSnapshot` bounds and validates accepted fields. Old password,
  username-selection and other obsolete fields do not return on migration.
  Web snapshots cannot introduce a new native key/pending login.
- Preserve authentication level separately from membership role. Preserve
  selected theme on logout; native theme selection wins over stale snapshots.

## Expiry, logout and unavailable verification

- `/api/auth/me` is authoritative for account and guest sessions.
  Native 401 raises expiry or performs the permitted HOSTSCREEN fallback;
  transient/offline/server errors preserve the saved login.
- Hosted `/auth/me` 401 and `auth:expired` map to expiry, not arbitrary
  mutation 401s. Adapter transport/timing details live in
  [bridge](../../webview-adapter/references/bridge.md).
- Native `signOut`/switch-user/switch-server revoke via `/api/auth/logout`
  before clearing identity and WebView data. Successful or already-expired
  logout permits clearing; failure retains login and asks for retry.
- `signedOut` clears the key and identity; `expired` may preserve the
  HOSTSCREEN key. Do not conflate these states or replay failed mutations.
  Independent browser cookies/login are not cleared by native logout.
