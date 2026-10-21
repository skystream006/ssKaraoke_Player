---
name: repo-router
description: >-
  Routes ambiguous, repository-wide, or cross-domain ssKaraoke Player
  requests to native-ui, identity-session, webview-adapter, or app-delivery.
  Use when ownership or the entry point is unclear; skip when a domain's
  metadata already matches precisely.
---

# Route before exploring

1. Read [routing](references/routing.md), not the whole repository.
2. Select one primary Skill, its first file/symbol, and its test shortlist.
3. Load only that Skill's relevant reference and affected source sections.
4. Consult [shared boundaries](references/common.md) only when a shared
   contract or external dependency is implicated.
5. Add another Skill only for a concrete dependency; state the reason.
6. Treat `MainActivity` as symbol-scoped, not one indivisible domain.
7. Do not assume the hosted frontend/backend exists in this checkout.
8. Use [validation](references/validation.md) for shared command syntax.
   Report checks performed, skipped checks, and external prerequisites.

Resolve links relative to their containing file and use absolute checkout
paths for filesystem tools. Keep source maps in references, not metadata.
The [routing reference](references/routing.md#discovery-and-loading) explains
the consuming agent's discovery prerequisite.
