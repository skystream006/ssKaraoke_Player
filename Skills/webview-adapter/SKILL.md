---
name: webview-adapter
description: >-
  Handles WebView integration, injected JavaScript/CSS, storage and
  tab restoration, native messaging, hosted mobile styling, swipes,
  drag compatibility, and browser adapter tests. Use for behavior
  at the Android-to-hosted-frontend boundary, not native-only layouts.
---

# Hosted-web adaptation

- Read [map](references/map.md); start with the implicated asset or
  `MainActivity` WebView symbol, not native UI helpers.
- Consult [bridge](references/bridge.md) when changing injection, message
  types, lifecycle handling or hosted-client integration.
- Preserve origin/main-frame restrictions and document-start restoration.
  Keep the hosted client authoritative for permissions and mutations.
- Consult [identity-session](../identity-session/SKILL.md) for session/route
  contracts, [native-ui](../native-ui/SKILL.md) for native input or shared
  themes, and [app-delivery](../app-delivery/SKILL.md) for test-tooling changes.
- Ignore updater/release internals and external frontend source until
  evidence requires them.
- Select Node and Playwright checks from [tests](references/tests.md).
  Real drag-library verification requires the external frontend build;
  fixture success alone does not prove that integration.

Resolve links relative to their containing file and use absolute checkout
paths for tools. Read [shared ownership](../repo-router/references/common.md#shared-contracts)
only for the contracts affected by this request.
