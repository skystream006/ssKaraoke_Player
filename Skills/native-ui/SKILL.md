---
name: native-ui
description: >-
  Handles native Android screens, settings, dialogs, accessibility,
  theme rendering, system bars, and keyboard/remote pointer input.
  Use for native presentation or input changes, not hosted web styling
  or authentication protocol changes.
---

# Native presentation and input

- Read [map](references/map.md); start at the affected `MainActivity`
  rendering symbol or `PointerNavigation`, not the entire activity.
- Inspect only relevant Android resources and palette entries.
- Preserve programmatic Material views, insets, accessible controls,
  form contents during recoloring, and per-window pointer dispatch.
- Consult [identity-session](../identity-session/SKILL.md) for saved-state
  or authentication semantics, [webview-adapter](../webview-adapter/SKILL.md)
  for shared theme/gesture behavior, and [app-delivery](../app-delivery/SKILL.md)
  for update behavior.
- Ignore protocol internals, web assets and release automation unless
  that dependency is demonstrated.
- Select `NativeUiTest` or `PointerNavigationTest` cases using
  [tests](references/tests.md). Broaden when shared UI helpers change.

Resolve links relative to their containing file and use absolute checkout
paths for filesystem tools. Shared ownership is indexed once in
[common boundaries](../repo-router/references/common.md); read it only
when the change crosses domains.
