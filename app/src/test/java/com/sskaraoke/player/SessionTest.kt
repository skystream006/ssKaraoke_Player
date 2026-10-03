package com.sskaraoke.player

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

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

    @Test fun seedNeverContainsPasswordAndSnapshotNeverReplacesIt() {
        val saved = SavedSession(origin = "https://karaoke.example", token = "token", level = "admin", password = "private")
        assertFalse(saved.seed().toString().contains("private"))
        val changed = saved.withSnapshot(JSONObject().put("token", "token").put("level", "admin")
            .put("password", "untrusted").put("route", "https://evil.example/settings").put("username", "Alex"))
        assertEquals("private", changed.password)
        assertEquals("/", changed.route)
        assertEquals(changed, SavedSession.fromJson(changed.toJson()))
        assertEquals("", changed.signedOut().password)
        assertEquals("", changed.signedOut().token)
    }

    @Test fun switchingUserKeepsLoginAndClearsOnlyTheActiveMemberAndLocation() {
        for (level in listOf("member", "admin")) {
            val saved = SavedSession(origin = "https://karaoke.example", token = "valid", level = level,
                password = "secret", username = "Alex", memberId = "member-1", memberName = "Alex",
                memberRole = "organizer", route = "/organizer/party-1/member-1", theme = "forest", view = "{\"tab\":\"search\"}")
            val switched = saved.switchUser()
            assertEquals(saved.copy(usernameRequired = true, memberId = "", memberName = "", memberRole = "", route = "/", view = "{}"), switched)
            assertEquals(switched, SavedSession.fromJson(switched.toJson()))
            assertTrue(switched.seed().getBoolean("usernameRequired"))
            assertFalse(switched.seed().has("password"))
        }
    }

    @Test fun switchingQrUserDoesNotRequestAPasswordOrChangeAuthenticationKind() {
        val switched = SavedSession(origin = "https://karaoke.example", token = "qr-token", level = "member", kind = "qr").switchUser()
        assertTrue(switched.usernameRequired)
        assertEquals("qr", switched.kind)
        assertEquals("qr-token", switched.token)
        assertEquals("", switched.password)
        assertEquals(SavedSession(), SavedSession().switchUser())
    }

    @Test fun validSessionDoesNotResendPassword() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))
            val saved = SavedSession(origin = server.url("/").toString(), token = "valid", level = "member", password = "secret")
            assertEquals(saved, SessionClient().resume(saved))
            assertEquals("Bearer valid", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun expiredPasswordSessionRenewsAtTheSavedServer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"token":"renewed","level":"member"}"""))
            val saved = SavedSession(origin = server.url("/").toString(), token = "old", level = "member", password = "secret", username = "Alex", route = "/guest/1/2")
            val renewed = SessionClient().resume(saved)
            assertEquals("renewed", renewed.token)
            assertEquals("Alex", renewed.username)
            assertEquals("/guest/1/2", renewed.route)
            server.takeRequest()
            val login = server.takeRequest()
            assertEquals("/api/auth", login.path)
            assertEquals("secret", JSONObject(login.body.readUtf8()).getString("password"))
            assertNull(login.getHeader("Authorization"))
        }
    }

    @Test fun qrSessionRenewsWithoutInventingAPassword() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"token":"qr-new","level":"member"}"""))
            val saved = SavedSession(origin = server.url("/").toString(), token = "old", kind = "qr")
            assertEquals("qr-new", SessionClient().resume(saved).token)
            server.takeRequest()
            assertEquals("/api/auth/qr-session", server.takeRequest().path)
        }
    }

    @Test fun failedRenewalRequiresLoginWithoutRetryLoop() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            val saved = SavedSession(origin = server.url("/").toString(), token = "old", password = "changed")
            try {
                SessionClient().resume(saved)
                fail("Expected sign-in requirement")
            } catch (_: SignInRequired) { assertEquals(2, server.requestCount) }
        }
    }

    @Test fun redirectsAndForbiddenResponsesDoNotLeakCredentialsOrTriggerRenewal() = runBlocking {
        for (status in listOf(302, 403, 500)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", "https://evil.example"))
                try {
                    SessionClient().resume(SavedSession(origin = server.url("/").toString(), token = "old", password = "secret"))
                    fail("Expected HTTP failure")
                } catch (failure: IOException) {
                    assertFalse(failure is SignInRequired)
                    assertEquals(1, server.requestCount)
                }
            }
        }
    }
}