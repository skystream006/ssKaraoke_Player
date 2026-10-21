# Native UI source map

## Find the smallest entry point

| Change | Source and symbols |
| --- | --- |
| Floating settings control, insets, initial screen | [MainActivity](../../../app/src/main/java/com/sskaraoke/player/MainActivity.kt): `buildChrome`, `applyInsets`, `showServerSetup` |
| Settings layout, swatches, action grid | Same activity: rendering inside `showSettings`; leave action semantics to their owners |
| Sign-in/error screen presentation | Same activity: rendering inside `showSignInOptions`, `showHostScreenSignIn`, `showServerConnectionError`, `showError`, `showBrowserAccount` |
| Common controls/dialogs | Same activity: `formPage`, `text`, `section`, `button`, `inputField`, `confirm`, `alertBuilder`, `themeDialog` |
| Native colors/system bars | Same activity: `themeColor`, `applyNativeTheme`, `themeSystemBars`, `tintView`; [palette](../../../app/src/main/assets/color-themes.json) |
| Remote pointer/keyboard | [PointerNavigation](../../../app/src/main/java/com/sskaraoke/player/PointerNavigation.kt): `PointerMotion.move`, `dispatchKeyEvent`, `sendTouch`, `reset`, `install` |
| Base style/API-specific styles | [values/styles.xml](../../../app/src/main/res/values/styles.xml), [values-v27/styles.xml](../../../app/src/main/res/values-v27/styles.xml) |
| Explicit branding/asset changes | [strings](../../../app/src/main/res/values/strings.xml), [icon](../../../app/src/main/res/drawable/app_icon.png), [font](../../../app/src/main/res/font/manrope.ttf), [font license](../../../app/src/main/assets/font-licenses/Manrope-OFL.txt) |

## Preserve these conventions

- Native views are built programmatically with Material/AppCompat controls.
  Do not introduce Compose or a separate layout framework for a local fix.
- Preserve system-bar/cutout/IME insets, content descriptions, touch targets,
  narrow-width and enlarged-font behavior. Settings is a separate dialog,
  not the hosted server administration screen.
- Theme changes recolor existing views without recreating forms or losing
  entered text. API-specific styles inherit the common base.
- Pointer movement integrates elapsed time and density, caps stalls and
  normalizes diagonal movement. Avoid WebView/layout work on each frame.
- Install once per `Window`, including dialogs. Dispatch synthetic touches
  through the saved `Window.Callback`; sending them through decor dispatch
  would re-enter the wrapper and cancel the gesture.
- Reset pointer state on real touch, focus loss and pause. Preserve ordinary
  keyboard/IME and screen-reader touch-exploration behavior.

## Expand or stop

Use [shared ownership](../../repo-router/references/common.md#shared-contracts)
for palette-key persistence and web consumers. A native-only spacing or text
fix does not require authentication internals, browser scripts, updater
networking or workflows. If a button's action changes, consult its owner
instead of changing behavior as an incidental layout fix.
