package com.sskaraoke.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
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

class SessionClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .callTimeout(20, TimeUnit.SECONDS).build()) {

    suspend fun resume(saved: SavedSession): SavedSession = withContext(Dispatchers.IO) {
        if (saved.origin.isBlank() || saved.token.isBlank()) return@withContext saved
        val server = ServerAddress.parse(saved.origin)
        val request = Request.Builder().url(server.api("auth/me"))
            .header("Authorization", "Bearer" + " " + saved.token).build()
        client.newCall(request).awaitResponse().use { response ->
            if (response.code == 401) throw SignInRequired("Your session ended. Sign in with a passkey or scan a current host invitation.")
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