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

    suspend fun resume(saved: SavedSession, forceRenew: Boolean = false): SavedSession = withContext(Dispatchers.IO) {
        if (saved.origin.isBlank() || saved.token.isBlank() && !forceRenew) return@withContext saved
        val server = ServerAddress.parse(saved.origin)
        if (!forceRenew) {
            val request = Request.Builder().url(server.api("parties")).header("Authorization", "Bearer ${saved.token}").build()
            client.newCall(request).awaitResponse().use { response ->
                if (response.isSuccessful) return@withContext saved
                if (response.code != 401) throw IOException("Server returned HTTP ${response.code}. Sign-in has been preserved.")
            }
        }
        val request = when {
            saved.kind == "qr" -> Request.Builder().url(server.api("auth/qr-session")).build()
            saved.password.isNotEmpty() -> Request.Builder().url(server.api("auth"))
                .post(JSONObject().put("password", saved.password).toString().toRequestBody("application/json".toMediaType())).build()
            else -> throw SignInRequired("Your session expired. Please sign in again.")
        }
        client.newCall(request).awaitResponse().use { response ->
            if (response.code == 401 || response.code == 403) throw SignInRequired("Your saved sign-in is no longer accepted. Please sign in again.")
            if (!response.isSuccessful) throw IOException("Sign-in service returned HTTP ${response.code}. Try again shortly.")
            val result = JSONObject(response.body?.string() ?: throw IOException("Empty sign-in response."))
            val token = result.optString("token")
            val level = result.optString("level")
            if (token.isEmpty() || token.length > 8192 || level !in setOf("member", "admin")) throw IOException("Invalid sign-in response.")
            saved.copy(token = token, level = level)
        }
    }

    fun cancel() { client.dispatcher.cancelAll() }
}