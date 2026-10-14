package com.sskaraoke.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, exception: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(exception)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, delivered, _ -> delivered.close() }
        }
    })
}

class SignInRequired(message: String) : IOException(message)

class HostScreenSignInException(message: String) : IOException(message)

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSignInException(message: String) : IOException(message), CopyableThrowable<BrowserSignInException> {
    init {
        initCause(null)
    }

    override fun createCopy(): BrowserSignInException? = null
}

class SessionClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .callTimeout(20, TimeUnit.SECONDS).build()) {

    private val exchangeClient = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(20, TimeUnit.SECONDS).build()

    private fun oneShotJsonBody(payload: JSONObject): RequestBody {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        return object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            // Sign-in requests must not be replayed by HTTP follow-up attempts.
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
    }

    suspend fun exchange(authorization: BrowserAuthorizationCode): SavedSession = withContext(Dispatchers.IO) {
        try {
            val origin = BrowserAuth.validatedOrigin(authorization.origin)
            BrowserAuth.validateCode(authorization.code)
            BrowserAuth.challenge(authorization.verifier)
            val payload = JSONObject().put("code", authorization.code)
                .put("code_verifier", authorization.verifier).put("redirect_uri", BrowserAuth.REDIRECT_URI)
            val request = Request.Builder().url(ServerAddress.parse(origin).api("auth/app/token"))
                .header("Accept", "application/json").post(oneShotJsonBody(payload)).build()
            exchangeClient.newCall(request).awaitResponse().use { response ->
                require(response.isSuccessful)
                response.accountSession(origin, setOf("member", "admin"))
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw BrowserSignInException("Browser sign-in failed. Please try again.")
        }
    }

    suspend fun loginHostScreen(origin: String, key: String): SavedSession = withContext(Dispatchers.IO) {
        var message = "HOSTSCREEN sign-in failed. Please try again."
        try {
            require(key.isNotBlank())
            val server = ServerAddress.parse(origin)
            val request = Request.Builder().url(server.api("auth/hostscreen/login"))
                .header("Accept", "application/json")
                .post(oneShotJsonBody(JSONObject().put("key", key))).build()
            exchangeClient.newCall(request).awaitResponse().use { response ->
                message = when (response.code) {
                    401 -> "Invalid HOSTSCREEN login key."
                    403 -> "The HOSTSCREEN account is unavailable."
                    404, 503 -> "HOSTSCREEN login is not available. Ask the server administrator to enable it."
                    429 -> "Too many sign-in attempts. Wait before trying again."
                    else -> message
                }
                require(response.isSuccessful)
                response.accountSession(server.origin, setOf("hostscreen")).copy(route = "/join")
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw HostScreenSignInException(message)
        }
    }

    private fun Response.accountSession(origin: String, allowedLevels: Set<String>): SavedSession {
        val responseBody = body ?: throw IOException()
        val source = responseBody.source()
        require(responseBody.contentLength() <= 64 * 1024 && !source.request(64 * 1024L + 1))
        val result = JSONObject(source.readUtf8())
        val token = result.opt("token") as? String ?: throw IOException()
        val level = result.opt("level") as? String ?: throw IOException()
        val username = (result.optJSONObject("user")?.opt("name") as? String)?.trim() ?: throw IOException()
        require(token.length in 1..8192 && token.all { it.code in 33..126 })
        require(level in allowedLevels)
        require(username.length in 1..60 && username.codePoints().noneMatch {
            Character.isISOControl(it) || it in 0xD800..0xDFFF || it == 0xFFFD
        })
        val expiresAt = when (val expiry = result.opt("expiresAt")) {
            is String -> {
                require(expiry.length <= 64)
                Instant.parse(expiry)
            }
            is Long -> Instant.ofEpochMilli(expiry)
            is Int -> Instant.ofEpochMilli(expiry.toLong())
            else -> throw IOException()
        }
        require(expiresAt.isAfter(Instant.now()))
        return SavedSession(origin = origin, token = token, level = level, username = username)
    }

    suspend fun resume(saved: SavedSession): SavedSession = withContext(Dispatchers.IO) {
        if (saved.origin.isBlank() || saved.token.isBlank()) return@withContext saved
        val server = ServerAddress.parse(saved.origin)
        val request = Request.Builder().url(server.api("auth/me"))
            .header("Authorization", "Bearer" + " " + saved.token).build()
        client.newCall(request).awaitResponse().use { response ->
            if (response.code == 401) throw SignInRequired("Your session ended. Sign in with a passkey, a HOSTSCREEN key, or a current host invitation.")
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}. Sign-in has been preserved.")
            val result = JSONObject(response.body?.string() ?: throw IOException("Empty sign-in response."))
            val level = result.optString("level")
            if (level !in SavedSession.levels) throw IOException("Invalid sign-in response.")
            saved.copy(level = level, username = result.optJSONObject("user")?.optString("name")
                ?.trim()?.takeIf { it.length in 1..60 } ?: saved.username)
        }
    }

    suspend fun logout(saved: SavedSession) = withContext(Dispatchers.IO) {
        if (saved.origin.isBlank() || saved.token.isBlank()) return@withContext
        val request = Request.Builder().url(ServerAddress.parse(saved.origin).api("auth/logout"))
            .header("Authorization", "Bearer" + " " + saved.token)
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful && response.code != 401) {
                throw IOException("Sign-out failed. Retry to revoke this session and its invitations.")
            }
        }
    }

    fun cancel() { client.dispatcher.cancelAll() }
}