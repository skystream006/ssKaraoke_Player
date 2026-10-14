package com.sskaraoke.player

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SessionTest {
    @Test fun serverAddressesHaveExplicitOriginBoundaries() {
        assertEquals("https://karaoke.example", ServerAddress.parse("karaoke.example/").origin)
        assertEquals("/join/ABC123", ServerAddress.parse("http://192.168.1.5:3000/join/ABC123").initialRoute)
        val server = ServerAddress.parse("https://karaoke.example:8443")
        assertTrue(server.owns("https://karaoke.example:8443/guest/1/2"))
        assertFalse(server.owns("https://karaoke.example/guest/1/2"))
        assertFalse(server.owns("https://evil.example/guest/1/2"))
        assertEquals("/", server.safeRoute("https://evil.example/settings"))
        assertEquals("/guest/1/2", server.safeRoute("/guest/1/2?token=do-not-save"))
        listOf("", "https://user:secret@host", "file:///sdcard", "https://host/api", "https://host?password=x").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse(input) }
        }
    }

    @Test fun legacyPasswordsAndUsernameRequirementsAreDiscarded() {
        val saved = SavedSession.fromJson(JSONObject().put("origin", "https://karaoke.example")
            .put("token", "token").put("level", "admin").put("password", "private")
            .put("kind", "password").put("usernameRequired", true))
        assertFalse(saved.toJson().has("password"))
        assertFalse(saved.toJson().has("kind"))
        assertFalse(saved.seed().has("usernameRequired"))
        val changed = saved.withSnapshot(JSONObject().put("token", "token").put("level", "admin")
            .put("password", "untrusted").put("route", "https://evil.example/settings").put("username", "Alex"))
        assertFalse(changed.toJson().has("password"))
        assertEquals("/", changed.route)
        assertEquals(changed, SavedSession.fromJson(changed.toJson()))
        assertEquals("", changed.signedOut().token)
    }

    @Test fun accountAndGuestSessionsRoundTripAndSignOutClearsIdentity() {
        for (level in listOf("member", "admin", "hostscreen", "guest")) {
            val saved = SavedSession(origin = "https://karaoke.example", token = "valid", level = level,
                username = "Alex", memberId = "member-1", memberName = "Alex",
                memberRole = "organizer", route = "/organizer/party-1/member-1", theme = "forest", view = "{\"tab\":\"search\"}")
            assertEquals(saved, SavedSession.fromJson(saved.toJson()))
            assertEquals(level, saved.seed().getString("level"))
            assertEquals(SavedSession(origin = saved.origin, theme = saved.theme), saved.signedOut())
        }
    }

    @Test fun hostScreenLoginUsesOnlyTheExactKeyAndPersistsOnlyTheRestrictedSession() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(hostScreenResponse().toString()))
            val key = " fixture-host-key "
            val saved = SessionClient().loginHostScreen(server.url("/").toString(), key)
            val request = server.takeRequest()
            assertEquals("/api/auth/hostscreen/login", request.path)
            assertEquals("POST", request.method)
            assertNull(request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("key"), body.keys().asSequence().toSet())
            assertEquals(key, body.getString("key"))
            assertEquals("hostscreen", saved.level)
            assertEquals("HOSTSCREEN", saved.username)
            assertEquals("/join", saved.route)
            assertEquals("", saved.memberId)
            assertEquals("", saved.memberRole)
            assertNull(saved.pendingLogin)
            assertFalse(saved.toJson().toString().contains(key))
            assertFalse(saved.seed().has("key"))
            assertEquals(saved, SavedSession.fromJson(saved.toJson()))
        }
    }

    @Test fun hostScreenErrorsAreSanitizedAndNeverRedirectOrRetry() = runBlocking {
        for (status in listOf(302, 307, 401, 403, 404, 408, 429, 500, 503)) {
            MockWebServer().use { server ->
                val key = "fixture-private-host-key"
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"$key"}""")
                    .setHeader("Location", "/redirect").setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setBody(hostScreenResponse().toString()))
                try {
                    SessionClient().loginHostScreen(server.url("/").toString(), key)
                    fail("Expected HOSTSCREEN sign-in failure")
                } catch (failure: HostScreenSignInException) {
                    assertFalse(failure.stackTraceToString().contains(key))
                    assertEquals(1, server.requestCount)
                }
            }
        }
    }

    @Test fun hostScreenRejectsMalformedOrWrongRoleSessions() = runBlocking {
        val invalid = listOf(
            hostScreenResponse().put("level", "admin"),
            hostScreenResponse().put("level", "member"),
            hostScreenResponse().put("level", "guest"),
            hostScreenResponse().put("token", ""),
            hostScreenResponse().put("token", "x".repeat(8193)),
            hostScreenResponse().put("user", JSONObject.NULL),
            hostScreenResponse().put("user", JSONObject().put("name", "")),
            hostScreenResponse().put("expiresAt", "2000-01-01T00:00:00Z"),
            hostScreenResponse().put("expiresAt", JSONObject.NULL),
            hostScreenResponse().put("padding", "x".repeat(64 * 1024))
        )
        for (body in invalid) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body.toString()))
            try {
                SessionClient().loginHostScreen(server.url("/").toString(), "fixture-host-key")
                fail("Expected invalid session rejection")
            } catch (_: HostScreenSignInException) { assertEquals(1, server.requestCount) }
        }
    }

    @Test fun hostScreenResumesAndRevokesLikeOtherAccountSessions() = runBlocking {
        MockWebServer().use { server ->
            val body = hostScreenResponse().apply { remove("token") }
            server.enqueue(MockResponse().setBody(body.toString()))
            server.enqueue(MockResponse().setResponseCode(204))
            val saved = SavedSession(origin = server.url("/").toString(), token = "hostscreen-session", level = "hostscreen",
                username = "HOSTSCREEN", memberId = "screen-member", memberName = "HOSTSCREEN", memberRole = "organizer",
                route = "/organizer/party-1/screen-member")
            val client = SessionClient()
            assertEquals(saved, client.resume(saved))
            client.logout(saved)
            for (path in listOf("/api/auth/me", "/api/auth/logout")) {
                val request = server.takeRequest()
                assertEquals(path, request.path)
                assertEquals(listOf("Bearer", saved.token).joinToString(" "), request.getHeader("Authorization"))
            }
            assertEquals("", saved.signedOut().token)
        }
    }

    private fun hostScreenResponse() = JSONObject().put("token", "hostscreen-session").put("level", "hostscreen")
        .put("user", JSONObject().put("name", "HOSTSCREEN").put("role", "hostscreen"))
        .put("partyId", JSONObject.NULL).put("memberId", JSONObject.NULL)
        .put("expiresAt", Instant.now().plusSeconds(3600).toString())

    @Test fun invitationFragmentsArePreservedOnlyForCanonicalSameOriginLinks() {
        val token = "a".repeat(41) + "_-"
        val route = "/join/invite#invite=$token"
        val server = ServerAddress.parse("https://karaoke.example$route")
        assertEquals("https://karaoke.example", server.origin)
        assertEquals(route, server.initialRoute)
        assertEquals(route, server.safeRoute(route))
        assertEquals("/account", server.safeRoute("/account"))
        assertEquals("/", server.safeRoute("https://evil.example$route"))
        assertEquals("/join/invite", server.safeRoute("/join/invite#invite=invalid"))
        assertEquals("/join/invite", server.safeRoute("/join/invite?other=1#invite=$token"))
        assertEquals("/settings", server.safeRoute("/settings#invite=$token"))
        listOf("https://karaoke.example/#invite=$token", "https://karaoke.example/join/invite#invite=short",
            "https://karaoke.example$route&other=1").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse(it) }
        }
        val saved = SavedSession(origin = server.origin, route = route)
        assertEquals(route, SavedSession.fromJson(saved.toJson()).route)
    }

    @Test fun validSessionUsesMeWithoutSigningInAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"level":"member","user":{"name":"Alex"}}"""))
            val saved = SavedSession(origin = server.url("/").toString(), token = "valid", level = "member", username = "Alex")
            assertEquals(saved, SessionClient().resume(saved))
            assertEquals("Bearer valid", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun expiredSessionRequiresPasskeyWithoutCallingRemovedEndpoints() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val saved = SavedSession(origin = server.url("/").toString(), token = "old", level = "member")
            try {
                SessionClient().resume(saved)
                fail("Expected sign-in requirement")
            } catch (_: SignInRequired) {
                assertEquals(1, server.requestCount)
                assertEquals("/api/auth/me", server.takeRequest().path)
            }
        }
    }

    @Test fun guestSessionCanResumeWithoutAccountOnlyPartiesRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"level":"guest","user":null,"partyId":"party","memberId":"guest"}"""))
            val saved = SavedSession(origin = server.url("/").toString(), token = "guest-session", level = "guest",
                memberId = "guest", memberName = "Alex", route = "/guest/party/guest")
            assertEquals(saved, SessionClient().resume(saved))
            assertEquals("/api/auth/me", server.takeRequest().path)
        }
    }

    @Test fun meUpdatesAccountNameAndAccessLevel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"level":"member","user":{"name":"Blair"}}"""))
            val saved = SavedSession(origin = server.url("/").toString(), token = "valid", level = "admin", username = "Alex")
            assertEquals(saved.copy(level = "member", username = "Blair"), SessionClient().resume(saved))
        }
    }

    @Test fun redirectsAndForbiddenResponsesDoNotLeakCredentialsOrTriggerRenewal() = runBlocking {
        for (status in listOf(302, 403, 500)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", "https://evil.example"))
                try {
                    SessionClient().resume(SavedSession(origin = server.url("/").toString(), token = "old"))
                    fail("Expected HTTP failure")
                } catch (failure: IOException) {
                    assertFalse(failure is SignInRequired)
                    assertEquals(1, server.requestCount)
                }
            }
        }
    }

    @Test fun logoutRevokesSavedTokenAndAcceptsAlreadyExpiredSessions() = runBlocking {
        for (status in listOf(200, 204, 401)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status))
            SessionClient().logout(SavedSession(origin = server.url("/").toString(), token = "valid"))
            val request = server.takeRequest()
            assertEquals("/api/auth/logout", request.path)
            assertEquals("POST", request.method)
            assertEquals(listOf("Bearer", "valid").joinToString(" "), request.getHeader("Authorization"))
            assertEquals("{}", request.body.readUtf8())
        }
    }

    @Test fun failedLogoutDoesNotFollowRedirectsOrSucceedSilently() = runBlocking {
        for (status in listOf(302, 403, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", "https://evil.example"))
            try {
                SessionClient().logout(SavedSession(origin = server.url("/").toString(), token = "valid"))
                fail("Expected sign-out failure")
            } catch (_: IOException) { assertEquals(1, server.requestCount) }
        }
    }

    @Test fun signedOutSessionsNeedNoNetworkRequest() = runBlocking {
        val saved = SavedSession(origin = "https://karaoke.example")
        assertEquals(saved, SessionClient().resume(saved))
        SessionClient().logout(saved)
    }
}