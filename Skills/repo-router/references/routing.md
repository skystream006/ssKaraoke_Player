# Routing index

Start narrow: choose by requested behavior, not by the folder containing
the code. A clear request can load its domain directly without this router.
If ownership remains ambiguous, ask which screen/flow is affected before
opening all domains.

| Symptom or request | Primary Skill | First reference and symbol |
| --- | --- | --- |
| Native setup/settings, dialogs, clipping, accessibility, colors, remote input | [native-ui](../../native-ui/SKILL.md) | [Map](../../native-ui/references/map.md): affected rendering method or `PointerNavigation` |
| Server connection, login/logout, HOSTSCREEN, callback, invitation URL, saved identity | [identity-session](../../identity-session/SKILL.md) | [Map](../../identity-session/references/map.md): `SessionClient`, `BrowserAuth`, `SavedSession`, or `ServerAddress` |
| Hosted-page layout, WebView bridge/navigation, web storage, tabs, swipes, drag compatibility | [webview-adapter](../../webview-adapter/SKILL.md) | [Map](../../webview-adapter/references/map.md): affected asset or `showWebView` |
| APK update/download/installer | [app-delivery](../../app-delivery/SKILL.md) | [Runtime](../../app-delivery/references/runtime.md): `UpdateClient`, `UpdateSecurity`, or install methods |
| Gradle, dependencies, signing/versioning, release automation, test-runner configuration | [app-delivery](../../app-delivery/SKILL.md) | [Build/release](../../app-delivery/references/build-release.md): affected configuration only |

Feature-test changes follow the owning domain. Test tooling/configuration
changes use app-delivery, consulting the domain whose suite is affected.
Server business logic is external; read [common boundaries](common.md)
before assuming a missing API implementation belongs here.

## Cross-domain workflow

1. Name the primary owner and locate its source symbol and relevant tests.
2. Read only the relevant reference section; do not load every linked file.
3. Identify the changed contract, if any, using [shared ownership](common.md#shared-contracts).
4. Consult the adjacent owner only for that contract.
5. Define the contract before updating consumers; each Skill remains
   authoritative for its own implementation.
6. Run the union of impacted tests, broadening for shared helpers, lifecycle
   behavior, or toolchain changes. Merely calling another domain is not a
   reason to load all of it.

## Five routing checks

| Realistic request | Initial scope | Conditional expansion | Smallest useful tests |
| --- | --- | --- | --- |
| Fix clipped App settings controls at large font sizes | native-ui: `showSettings`, sizing helpers | None for presentation-only changes | `NativeUiTest.serverActionsUseThreeColumnsWithoutClippingLabels`, `updateButtonFitsALargeFontOnANarrowPhone` |
| Recover HOSTSCREEN after session expiry | identity-session: `SessionClient.resume`, `SavedSession.expired` | webview-adapter if hosted expiry delivery is involved | HOSTSCREEN `SessionTest` cases and affected native expiry tests; browser expiry tests only if implicated |
| Stop queue swipes interrupting song dragging | webview-adapter: touch exclusions, `swipeDirection` | native-ui only if remote-generated touch events cause it | Node swipe test, browser swipe test, real-frontend touch-drag test |
| Add a color theme across the app | native-ui: palette and picker | identity-session for saved-key acceptance; webview-adapter for web application/restoration | Native theme cases, saved-state acceptance, browser theme/reload test |
| Change release APK filename used by updates | app-delivery: release producer and `ReleaseInfo` consumer | native-ui only if presentation changes | `UpdateTest`, relevant workflow assertions, release build/metadata |

For the theme example: palette/key definition → persisted-key acceptance →
native rendering and WebView application → native/session/browser tests.
Do not turn this into an unrelated authentication or release refactor.

## Discovery and loading

These files are a documentation catalog, not an installed agent plugin.
The consuming agent must support registering this repository's root
`Skills` directory (or individually registering the five Skill directories).
Use that agent's supported discovery mechanism; this catalog does not
assume a vendor-specific configuration key.

Index only each `SKILL.md`'s YAML `name` and `description` initially.
Load the selected body next, then specific `references` sections on demand.
Register metadata for direct domain activation as well as router activation.
If custom search roots are unsupported, configure an appropriate supported
installation outside this documentation-only change; do not claim these
files activate automatically or copy the full map into always-loaded text.

When maintaining this catalog, verify the five cases above still route
correctly and all referenced files/symbols exist. Update the owning reference
when a boundary changes rather than duplicating knowledge across Skills.
