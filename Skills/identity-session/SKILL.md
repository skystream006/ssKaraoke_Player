---
name: identity-session
description: >-
  Handles server connectivity, address and invitation validation,
  browser PKCE and HOSTSCREEN login, logout, session restoration,
  expiry, and encrypted persistence. Use for identity, credential,
  callback, revocation, or saved-session behavior changes.
---

# Identity and durable session state

- Read [map](references/map.md) and open only the implicated class and
  `MainActivity` transition methods.
- Read the relevant section of [contracts](references/contracts.md) before
  changing API, state, URL or JavaScript-visible contracts.
- Preserve origin isolation, one-shot exchanges, encrypted durable state,
  native-only secret exclusion from WebView seeds, and distinct
  expiry/logout/outage handling. Seeds intentionally contain session tokens.
- Consult [webview-adapter](../webview-adapter/SKILL.md) for bridge/storage
  parity, [native-ui](../native-ui/SKILL.md) for presentation, and
  [app-delivery](../app-delivery/SKILL.md) if the shared HTTP helper changes.
- Ignore queue/playback implementation, pointer code and release automation
  unless directly implicated.
- Use [tests](references/tests.md) for JVM, activity, bridge and device-test
  selection. Compiling instrumentation is not device persistence verification.

Resolve links relative to their containing file and use absolute checkout
paths for tools. [Shared ownership](../repo-router/references/common.md#shared-contracts)
is a conditional reference, not mandatory context for every login fix.
