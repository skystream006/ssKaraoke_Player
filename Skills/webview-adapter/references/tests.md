# Adapter test selection

Use [shared commands](../../repo-router/references/validation.md), then choose
the matching case below. No local frontend build command exists.

## Pure helpers

Source: [mobile-session.test.cjs](../../../tests/mobile-session.test.cjs).
Run this file for changes to `mobile-session.js`; optional Node title filters:

- Restoration/privacy: `a recreated WebView restores account and guest sessions before React starts`;
  `HOSTSCREEN keeps its authentication level separately from organizer membership and never exposes the native key`.
- Routes: `only known same-origin karaoke locations are persisted`;
  `only canonical invitation URLs retain their 43-character base64url fragment`.
- Gestures: `swipes must be deliberate horizontal movements, not scrolling or long-press dragging`.

## Browser integration

Source: [adapter.spec.cjs](../../../tests/browser/adapter.spec.cjs).
These are existing `--grep` title fragments, not invented suite names:

| Behavior | Filter |
| --- | --- |
| Seed/tab restoration | `restores login, member identity, theme` |
| Theme application/reload | `all theme colors update in place` |
| Scoped toolbar changes | `keeps sign-out in the party header` |
| Gesture exclusions | `a horizontal swipe switches tabs` |
| Real drag sensor/reorder request | `real server playlist reorders` |
| Explicit logout and expiry races | `logout|sign out|sign-out|auth:expired` |
| Verification outage/stale response | `verification outage|/auth/me|stale` |
| Account routing | `account route|SPA account navigation` |
| Hosted sign-in/invitation checkpoint | `hosted sign-in routing` |
| Immediate persistence | `synchronously checkpoint|without waiting for a debounce` |

Retain phone and tablet projects for layout/gesture/theme changes. Inspect
selection with `--list`, and check runtime skip reasons. Run the whole adapter
suite when modifying global fetch/XHR/storage/history wrappers.

Default mode uses [fixture.html](../../../tests/browser/fixture.html) and
mock API responses. The `fixture AuthContext contract` group and some race
tests skip in external-frontend mode. The real touch-drag test skips without
`KARAOKE_FRONTEND_BUILD`; set it to an existing absolute external build path
to exercise the hosted drag library. Run both modes when both contracts change.
[fixture-server.cjs](../../../tests/browser/fixture-server.cjs) does not provide
live Socket.IO, backend authorization or real passkey verification.

Native bridge/seed/route changes add the relevant
[identity tests](../../identity-session/references/tests.md), Android lint and
debug assembly. Palette changes add [native theme tests](../../native-ui/references/tests.md).
Playwright simulates `KaraokeHost`; it cannot establish actual Android
origin-scoped bridge enforcement, WebView provider support or device playback.
Verify affected platform boundaries on a device.
