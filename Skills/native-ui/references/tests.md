# Native UI test selection

Use [shared commands](../../repo-router/references/validation.md) with the
class/method below. Start with the affected behavior; run the full class
when changing common controls, dialog theming or input dispatch.

## NativeUiTest

Source: [NativeUiTest.kt](../../../app/src/test/java/com/sskaraoke/player/NativeUiTest.kt).
Robolectric exercises narrow layouts and selected Android API levels;
`PreviewActivity` disables automatic update checks.

| Behavior | Existing method anchors |
| --- | --- |
| Initial controls and settings accessibility | `firstLaunchHasServerEntryAndAccessibleSettings`, `settingsHasARealUpdateButtonAndAllSixSwatchesBeforeLogin` |
| Settings grid and large fonts | `serverActionsUseThreeColumnsWithoutClippingLabels`, `updateButtonFitsALargeFontOnANarrowPhone` |
| Theme inheritance and recoloring | `themePreservesSharedStylesAndUsesLightNavigationIcons`, `allThemesRecolorNativeViewsWithoutDiscardingFormInput`, `staleWebSnapshotsCannotUndoNativeThemeSelection` |
| Home/switch-user controls | `homeButtonClosesSettingsAndPreservesTheCurrentUser`, `switchUserRequiresConfirmationBeforeRevokingAccountSession` |

Authentication semantics use the [identity test map](../../identity-session/references/tests.md),
not every native layout test. Adding a theme may require updating explicit
six-theme expectations; preserve unrelated tests.

## PointerNavigationTest

Source: [PointerNavigationTest.kt](../../../app/src/test/java/com/sskaraoke/player/PointerNavigationTest.kt).

| Behavior | Existing method anchors |
| --- | --- |
| Movement/acceleration | `movementIsIndependentOfFrameRateAndDensity`, `diagonalsAreNormalizedAndOpposingKeysDoNotMove`, `stallsAreBoundedAndReleasingResetsAcceleration` |
| Click/drag and cancellation | `selectProducesOneTouchGestureAndCanDragWithoutMovingFocus`, `focusLossAndRealTouchCancelMovementAndDoNotRestartFromRepeats` |
| Accessibility/window integration | `touchExplorationKeepsDirectionalKeysAndMenuAndCancelsAnActivePointer`, `settingsAndConfirmationDialogsAlsoHavePointerNavigation`, `pausingTheActivityStopsThePointer` |

Add browser theme/gesture cases from the [adapter test map](../../webview-adapter/references/tests.md)
only for shared behavior. Run Android lint/build for native changes.
Robolectric cannot establish physical remote, real IME or screen-reader
usability; manually exercise those paths on a device when affected.
