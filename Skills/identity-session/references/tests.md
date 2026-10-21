# Identity/session test selection

Use [shared commands](../../repo-router/references/validation.md) with exact
class/method filters. Expand to the whole class for shared parsing/state changes.

| Change | Source and existing method anchors |
| --- | --- |
| Ping/connection policy | [SessionTest](../../../app/src/test/java/com/sskaraoke/player/SessionTest.kt): `pingChecksTheRootWithoutCredentialsOrInvitationSecrets`, `pingRejectsHttpErrorsWithoutFollowingRedirects`, `pingFallsBackToAnotherAddressAfterConnectionFailure`, `cancellingPingCancelsTheNetworkCall` |
| Origins/invitations/state migration | Same class: `serverAddressesHaveExplicitOriginBoundaries`, `invitationFragmentsArePreservedOnlyForCanonicalSameOriginLinks`, `legacyPasswordsAndUsernameRequirementsAreDiscarded`, `accountAndGuestSessionsRoundTripAndSignOutClearsIdentity` |
| HOSTSCREEN persistence/renewal | Same class: `hostScreenLoginSavesTheExactKeyOnlyInNativeStateAfterSuccess`, `hostScreenKeySurvivesExpiryButNotSignOutOrAnotherIdentity`, `expiredHostScreenAutomaticallySignsInOnceAndDiscardsOldPartyIdentity`, `automaticHostScreenLoginFailuresDoNotLoopOrLeakTheSavedKey`, `unavailableHostScreenVerificationNeverSubmitsTheSavedKey` |
| Resume/logout/errors | Same class: `validSessionUsesMeWithoutSigningInAgain`, `guestSessionCanResumeWithoutAccountOnlyPartiesRequest`, `redirectsAndForbiddenResponsesDoNotLeakCredentialsOrTriggerRenewal`, `logoutRevokesSavedTokenAndAcceptsAlreadyExpiredSessions`, `failedLogoutDoesNotFollowRedirectsOrSucceedSilently` |
| PKCE/origins/callbacks | [BrowserAuthTest](../../../app/src/test/java/com/sskaraoke/player/BrowserAuthTest.kt): `pkceMatchesTheRfc7636VectorAndLoginUrlContainsOnlyPublicParameters`, `browserLoginRequiresAnHttpsHostnameOrigin`, `callbackIsBoundToTheExactSchemeRawPathAndParameterSet`, `pendingLoginMustExistAndBeNoOlderThanTenMinutes` |
| Exchange/privacy/durable model | Same class: `pendingLoginPersistsNativelyButIsNeverSeededOrChangedByJavascript`, `exchangeUsesPkcePostAndAcceptsOnlyFlatApprovedSessions`, `exchangeRejectsMalformedUnapprovedOrExpiredSessions`, `responseSizeIsBoundedEvenWithAnUnknownContentLength`, `transportAndBodyReadFailuresDoNotLeakTheirRawMessages` |

## Activity integration

Source: [NativeUiTest](../../../app/src/test/java/com/sskaraoke/player/NativeUiTest.kt).
Select by the transition changed, not every layout case:

- Connectivity: `failedPingShowsServerDetailsAndRetryChecksAgainBeforeSignIn`,
  `backgroundingPingCancelsItAndRechecksOnResume`,
  `savedLoginIsPreservedWhenSessionVerificationIsUnavailable`.
- HOSTSCREEN: `savedHostScreenKeyAutomaticallyReplacesExpiredNativeSession`,
  `rejectedSavedHostScreenKeyReturnsToManualLoginWithoutRetrying`,
  `hostedExpiryKeepsHostScreenKeyButExplicitLogoutForgetsIt`,
  `switchingUserOrServerForgetsSavedHostScreenKeyAfterRevocation`.
- Browser: `pendingBrowserSignInSurvivesBackgroundButCancelRemovesIt`,
  `invalidAndUnsolicitedCallbacksCannotSignInOrConsumeValidPendingLogin`,
  `accountManagementOpensOnlyTheWebsiteWithoutAppCredentials`.

## Device and cross-boundary checks

[SessionStoreDeviceTest](../../../app/src/androidTest/java/com/sskaraoke/player/SessionStoreDeviceTest.kt)
executes `keystoreEncryptedSessionSurvivesRecreation`,
`pendingBrowserLoginIsEncryptedAndConsumedDurably`, and
`hostScreenKeyIsEncryptedAndRemovedOnSignOut`. Run it for persistence changes;
building the instrumentation APK alone does not execute these assertions.

Seed/snapshot/route or hosted-expiry changes also require the matching
[Node/browser tests](../../webview-adapter/references/tests.md).
Changes to `Call.awaitResponse` add [updater tests](../../app-delivery/references/tests.md).
Use lint/debug assembly for native changes. Live browser prompts, server
handoff, process death, private-CA networking and hardware behavior require
device/server checks beyond these mocks.
