package com.sskaraoke.player

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class BrowserAuthTest {
    private val origin = "https://karaoke.example"
    private val now = 1_800_000_000_000L
    private val code = "c".repeat(43)
    private val pending = PendingBrowserLogin(origin, "v".repeat(43), "s".repeat(43), now)
    private val authorization = BrowserAuthorizationCode(origin, code, pending.verifier)

    @Test fun generatedSecretsAreIndependentUnpadded32ByteValues() {
        val first = BrowserAuth.begin("$origin/", now)
        val second = BrowserAuth.begin(origin, now)
        assertEquals(origin, first.origin)
        assertEquals(now, first.createdAt)
        val secrets = listOf(first.state, first.verifier, second.state, second.verifier)
        assertEquals(4, secrets.toSet().size)
        secrets.forEach {
            assertTrue(it.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertEquals(32, Base64.getUrlDecoder().decode(it).size)
        }
    }

    @Test fun pkceMatchesTheRfc7636VectorAndLoginUrlContainsOnlyPublicParameters() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            BrowserAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val raw = BrowserAuth.loginUrl(pending)
        val url = raw.toHttpUrl()
        assertEquals("$origin/app-login", raw.substringBefore('?'))
        assertEquals(setOf("redirect_uri", "code_challenge", "code_challenge_method", "state"), url.queryParameterNames)
        assertEquals("com.sskaraoke.player:/oauth/callback", url.queryParameter("redirect_uri"))
        assertTrue(raw.contains("redirect_uri=com.sskaraoke.player%3A%2Foauth%2Fcallback"))
        assertEquals(BrowserAuth.challenge(pending.verifier), url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(pending.state, url.queryParameter("state"))
        assertFalse(raw.contains(pending.verifier))
        assertFalse(raw.contains("token="))
    }

    @Test fun browserLoginRequiresAnHttpsHostnameOrigin() {
        val invalid = listOf(
            "", "karaoke.example", "http://karaoke.example", "https://127.0.0.1", "https://[::1]",
            "https://127.1", "https://2130706433", "https://0x7f000001", "https://0177.0.0.1",
            "https://0x7f.0.0.1", "https://user@karaoke.example", "https://@karaoke.example",
            "$origin/app-login", "$origin/..", "$origin?other=1", "$origin#other", "$origin?",
            "https://karaoke.example\\@evil.example", " $origin", "https://%6baraoke.example",
            "https://-host.example", "https://host..example", "https://host.example:0"
        )
        invalid.forEach { address ->
            assertThrows(address, IllegalArgumentException::class.java) { BrowserAuth.begin(address, now) }
        }
        assertEquals("https://karaoke.example:8443", BrowserAuth.begin("https://KARAOKE.example:8443/", now).origin)
        assertEquals("https://localhost", BrowserAuth.begin("https://localhost", now).origin)
    }

    @Test fun callbackReturnsOnlyThePendingOriginAndVerifier() {
        assertEquals(authorization, BrowserAuth.acceptCallback(callback(), pending, now))
        assertEquals(authorization, BrowserAuth.acceptCallback(
            "${BrowserAuth.REDIRECT_URI}?state=${pending.state}&code=$code", pending, now))
        assertEquals(authorization, BrowserAuth.acceptCallback(
            "${BrowserAuth.REDIRECT_URI}?code=%63${code.drop(1)}&state=${pending.state}", pending, now))
    }

    @Test fun callbackIsBoundToTheExactSchemeRawPathAndParameterSet() {
        val query = "?code=$code&state=${pending.state}"
        val invalid = listOf(
            "https://karaoke.example/oauth/callback$query",
            "com.sskaraoke.player://oauth/callback$query",
            "com.sskaraoke.player:///oauth/callback$query",
            "COM.SSKARAOKE.PLAYER:/oauth/callback$query",
            "com.sskaraoke.player:/oauth/%63allback$query",
            "com.sskaraoke.player:/oauth/../oauth/callback$query",
            "com.sskaraoke.player:/oauth/callback/$query",
            "com.sskaraoke.player:oauth/callback$query",
            callback() + "#", callback() + "#token=secret", callback() + "&",
            callback() + "&origin=https%3A%2F%2Fevil.example",
            callback() + "&code=$code", callback() + "&state=${pending.state}",
            "${BrowserAuth.REDIRECT_URI}?code=$code&code=$code",
            "${BrowserAuth.REDIRECT_URI}?state=${pending.state}&%73tate=${pending.state}",
            "${BrowserAuth.REDIRECT_URI}?code=$code&token=${pending.state}",
            "${BrowserAuth.REDIRECT_URI}?code=$code",
            "${BrowserAuth.REDIRECT_URI}?code=$code;state=${pending.state}",
            "${BrowserAuth.REDIRECT_URI}?code=$code&state=",
            "${BrowserAuth.REDIRECT_URI}?code=&state=${pending.state}",
            "${BrowserAuth.REDIRECT_URI}?code=$code&state=${"x".repeat(43)}"
        )
        invalid.forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { BrowserAuth.acceptCallback(raw, pending, now) }
        }
    }

    @Test fun malformedEncodedOrOversizedCallbacksAreRejectedWithoutEchoingThem() {
        val invalidValues = listOf(
            "c".repeat(42), "c".repeat(44), "c".repeat(42) + "=", "c".repeat(42) + "+",
            "c".repeat(42) + "%", "c".repeat(42) + "%Q0", "c".repeat(42) + "%00",
            "c".repeat(42) + "%FF", "c".repeat(42) + "%C0%AF", "c".repeat(42) + "é",
            "c".repeat(42) + "%252F", "c".repeat(3000)
        )
        invalidValues.forEach { value ->
            val raw = "${BrowserAuth.REDIRECT_URI}?code=$value&state=${pending.state}"
            val failure = assertThrows(IllegalArgumentException::class.java) {
                BrowserAuth.acceptCallback(raw, pending, now)
            }
            assertFalse(failure.message.orEmpty().contains(pending.state))
            assertFalse(failure.message.orEmpty().contains(value))
        }
    }

    @Test fun pendingLoginMustExistAndBeNoOlderThanTenMinutes() {
        assertEquals(authorization, BrowserAuth.acceptCallback(callback(), pending, now + BrowserAuth.LOGIN_LIFETIME_MS))
        assertThrows(IllegalArgumentException::class.java) { BrowserAuth.acceptCallback(callback(), null, now) }
        for (time in listOf(now - 1, now + BrowserAuth.LOGIN_LIFETIME_MS + 1, Long.MAX_VALUE, -1)) {
            assertThrows(IllegalArgumentException::class.java) { BrowserAuth.acceptCallback(callback(), pending, time) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserAuth.acceptCallback(callback(), pending.copy(createdAt = Long.MIN_VALUE), now)
        }
    }

    @Test fun pendingJsonRoundTripsWithStrictFieldAndAgeValidation() {
        assertEquals(pending, PendingBrowserLogin.fromJson(pending.toJson(), now))
        val corrupt = listOf(
            pending.toJson().put("origin", "http://karaoke.example"),
            pending.toJson().put("origin", "$origin/app-login"),
            pending.toJson().put("origin", "$origin/"),
            pending.toJson().put("verifier", "short"),
            pending.toJson().put("state", 123),
            pending.toJson().put("createdAt", now.toString()),
            pending.toJson().put("createdAt", now.toDouble() + 0.5),
            pending.toJson().put("createdAt", now + 1),
            pending.toJson().put("createdAt", now - BrowserAuth.LOGIN_LIFETIME_MS - 1),
            pending.toJson().put("createdAt", -1),
            pending.toJson().apply { remove("state") }
        )
        corrupt.forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { PendingBrowserLogin.fromJson(json, now) }
        }
    }

    @Test fun pendingLoginPersistsNativelyButIsNeverSeededOrChangedByJavascript() {
        val login = BrowserAuth.begin(origin)
        val saved = SavedSession(origin = origin, theme = "forest", pendingLogin = login)
        assertEquals(saved, SavedSession.fromJson(saved.toJson()))
        assertEquals(login.toJson().toString(), saved.toJson().getJSONObject("pendingLogin").toString())
        val seed = saved.seed().toString()
        assertFalse(seed.contains("pendingLogin"))
        assertFalse(seed.contains("verifier"))
        assertFalse(seed.contains("state"))
        assertFalse(seed.contains(login.verifier))
        assertFalse(seed.contains(login.state))
        val forged = BrowserAuth.begin("https://evil.example")
        val snapshot = saved.seed().put("pendingLogin", forged.toJson())
            .put("verifier", forged.verifier).put("state", forged.state)
        assertEquals(login, saved.withSnapshot(snapshot).pendingLogin)
        assertNull(saved.signedOut().pendingLogin)
        assertEquals("forest", saved.signedOut().theme)
    }

    @Test fun staleMismatchedMalformedOrSignedInPendingStateIsDiscarded() {
        val login = BrowserAuth.begin(origin)
        val saved = SavedSession(origin = origin, pendingLogin = login)
        val invalid = listOf(
            saved.toJson().put("origin", "https://other.example"),
            saved.toJson().put("token", "signed-in").put("level", "member"),
            saved.toJson().put("token", "guest-session").put("level", "guest"),
            saved.toJson().put("token", "signed-in").put("level", "invalid"),
            saved.toJson().put("token", "x".repeat(8193)).put("level", "member"),
            saved.toJson().put("pendingLogin", JSONObject()),
            saved.toJson().put("pendingLogin", "not an object"),
            saved.toJson().put("pendingLogin", login.toJson().put("createdAt", 1)),
            saved.toJson().put("pendingLogin", login.toJson().put("createdAt", Long.MAX_VALUE)),
            saved.toJson().put("pendingLogin", login.toJson().put("origin", "http://karaoke.example"))
        )
        invalid.forEach { assertNull(SavedSession.fromJson(it).pendingLogin) }
    }

    @Test fun exchangeUsesPkcePostAndAcceptsOnlyFlatApprovedSessions() = runBlocking {
        for (level in listOf("member", "admin")) {
            var captured: Request? = null
            val client = mockClient { request ->
                captured = request
                response(request, validResponse(level).toString())
            }
            val saved = client.exchange(authorization)
            assertEquals(SavedSession(origin = origin, token = "approved-session", level = level, username = "Alex"), saved)
            val request = captured!!
            assertEquals("$origin/api/auth/app/token", request.url.toString())
            assertEquals("POST", request.method)
            assertNull(request.header("Authorization"))
            assertEquals("application/json", request.header("Accept"))
            assertEquals("application/json", request.body!!.contentType().toString())
            assertTrue(request.body!!.isOneShot())
            val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals(setOf("code", "code_verifier", "redirect_uri"), payload.keys().asSequence().toSet())
            assertEquals(code, payload.getString("code"))
            assertEquals(pending.verifier, payload.getString("code_verifier"))
            assertEquals(BrowserAuth.REDIRECT_URI, payload.getString("redirect_uri"))
            assertFalse(payload.has("state"))
        }
    }

    @Test fun exchangeAcceptsFutureEpochMillisecondsAndTrimsTheAccountName() = runBlocking {
        val client = mockClient { request ->
            response(request, validResponse().put("expiresAt", System.currentTimeMillis() + 60_000)
                .put("user", JSONObject().put("name", " Alex ")).toString())
        }
        assertEquals("Alex", client.exchange(authorization).username)
    }

    @Test fun exchangeRejectsRedirectsAndErrorsWithoutReturningOrLeakingASession() {
        for (status in listOf(301, 302, 303, 307, 308, 400, 401, 403, 408, 429, 500, 503)) {
            val attempts = AtomicInteger()
            val client = mockClient { request ->
                attempts.incrementAndGet()
                response(request, validResponse().toString(), status).newBuilder()
                    .header("Location", "https://evil.example/steal")
                    .header("Retry-After", "0").build()
            }
            assertSafeFailure(client)
            assertEquals(1, attempts.get())
        }
    }

    @Test fun exchangeRejectsMalformedUnapprovedOrExpiredSessions() {
        val invalid = listOf(
            validResponse().put("level", "guest"),
            validResponse().put("level", "pending"),
            validResponse().put("level", "none"),
            validResponse().put("level", 123),
            validResponse().put("token", ""),
            validResponse().put("token", " "),
            validResponse().put("token", "token\nsecret"),
            validResponse().put("token", "a".repeat(8193)),
            validResponse().put("token", 123),
            validResponse().put("user", JSONObject.NULL),
            validResponse().put("user", JSONObject().put("name", " ")),
            validResponse().put("user", JSONObject().put("name", "n".repeat(61))),
            validResponse().put("user", JSONObject().put("name", "A\nlex")),
            validResponse().put("user", JSONObject().put("name", 123)),
            validResponse().put("expiresAt", "not-a-date"),
            validResponse().put("expiresAt", Instant.now().minusSeconds(1).toString()),
            validResponse().put("expiresAt", System.currentTimeMillis() - 1),
            validResponse().put("expiresAt", System.currentTimeMillis() / 1000 + 3600),
            validResponse().put("expiresAt", 1.5),
            validResponse().apply { remove("expiresAt") },
            JSONObject().put("session", validResponse())
        ).map(JSONObject::toString) + listOf(
            "", "not-json approved-session", "[]", "{\"token\":",
            validResponse().toString().replace("\"Alex\"", "\"A\\uD800lex\"")
        )
        invalid.forEach { raw -> assertSafeFailure(mockClient { response(it, raw) }) }
    }

    @Test fun exchangeRejectsInvalidAuthorizationBeforeNetworkAccess() {
        val calls = AtomicInteger()
        val client = mockClient { request ->
            calls.incrementAndGet()
            response(request, validResponse().toString())
        }
        val invalid = listOf(
            authorization.copy(origin = "http://karaoke.example"),
            authorization.copy(origin = "https://127.0.0.1"),
            authorization.copy(origin = "$origin/other"),
            authorization.copy(code = "short"),
            authorization.copy(code = "c".repeat(42) + "\n"),
            authorization.copy(verifier = "short")
        )
        invalid.forEach { assertSafeFailure(client, it) }
        assertEquals(0, calls.get())
    }

    @Test fun responseSizeIsBoundedEvenWithAnUnknownContentLength() {
        val readBytes = AtomicInteger()
        val closed = AtomicInteger()
        val source = object : ForwardingSource(Buffer().writeUtf8(" ".repeat(100_000))) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) readBytes.addAndGet(it.toInt())
            }
            override fun close() { closed.incrementAndGet(); super.close() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
        }
        assertSafeFailure(mockClient { response(it, "").newBuilder().body(body).build() })
        assertTrue(readBytes.get() in 65_537..73_728)
        assertTrue(closed.get() > 0)
        assertSafeFailure(mockClient { response(it, " ".repeat(65_537)) })
    }

    @Test fun transportAndBodyReadFailuresDoNotLeakTheirRawMessages() {
        val attempts = AtomicInteger()
        assertSafeFailure(mockClient {
            attempts.incrementAndGet()
            throw IOException("private failure $code ${pending.verifier} approved-session")
        })
        assertEquals(1, attempts.get())
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("approved-session")
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
        }
        assertSafeFailure(mockClient { response(it, "").newBuilder().body(body).build() })
    }

    private fun callback() = "${BrowserAuth.REDIRECT_URI}?code=$code&state=${pending.state}"

    private fun validResponse(level: String = "member") = JSONObject()
        .put("token", "approved-session").put("level", level)
        .put("user", JSONObject().put("name", "Alex"))
        .put("expiresAt", Instant.now().plusSeconds(3600).toString())

    private fun mockClient(answer: (Request) -> Response): SessionClient = SessionClient(
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true)
            .addInterceptor { chain -> answer(chain.request()) }.build()
    )

    private fun response(request: Request, raw: String, status: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
            .message("test response").body(raw.toResponseBody()).build()

    private fun assertSafeFailure(client: SessionClient, value: BrowserAuthorizationCode = authorization) {
        val failure = assertThrows(IOException::class.java) { runBlocking { client.exchange(value) } }
        assertEquals("Browser sign-in failed. Please try again.", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains(code))
        assertFalse(failure.message.orEmpty().contains(pending.verifier))
        assertFalse(failure.message.orEmpty().contains("approved-session"))
    }
}
