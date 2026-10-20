---
name: app-delivery
description: >-
  Handles Android build dependencies, versioning, signing, release
  workflows, APK update discovery/download/verification, and installer
  handoff. Use for build, distribution, release, test-tooling configuration,
  or in-app update changes, not karaoke authentication.
---

# APK delivery

- Choose [runtime](references/runtime.md) for updater/installer work or
  [build-release](references/build-release.md) for toolchain and workflow
  work. Do not load both unless their shared contract changes.
- Preserve full-history versioning, package/signing continuity, approved
  download origins, integrity checks and user-approved installation.
- Keep releases manual and privileged automation isolated from PR code.
- Consult [native-ui](../native-ui/SKILL.md) for presentation,
  [identity-session](../identity-session/SKILL.md) for shared HTTP helper
  changes, and [webview-adapter](../webview-adapter/SKILL.md) for browser
  tooling or browser-suite behavior.
- Ignore karaoke authentication, browser assets and unrelated workflows.
- Select `UpdateTest`, workflow checks or build/lint tasks using
  [tests](references/tests.md). Identify device and publishing checks
  separately from mocked tests.

Resolve links relative to their containing file and use absolute checkout
paths for tools. Consult [shared ownership](../repo-router/references/common.md#shared-contracts)
only when changing a cross-domain contract.
