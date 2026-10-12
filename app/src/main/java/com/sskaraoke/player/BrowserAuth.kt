package com.sskaraoke.player

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PendingBrowserLogin(
    val origin: String,
    val verifier: String,
    val state: String,
    val createdAt: Long
) {
    fun toJson(): JSONObject {
        BrowserAuth.validatePending(this)
        return JSONObject().put("origin", origin).put("verifier", verifier)
            .put("state", state).put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject, now: Long = System.currentTimeMillis()): PendingBrowserLogin {
            val pending = PendingBrowserLogin(
                origin = json.opt("origin") as? String ?: invalidPending(),
                verifier = json.opt("verifier") as? String ?: invalidPending(),
                state = json.opt("state") as? String ?: invalidPending(),
                createdAt = when (val value = json.opt("createdAt")) {
                    is Long -> value
                    is Int -> value.toLong()
                    else -> invalidPending()
                }
            )
            BrowserAuth.validatePending(pending, now)
            return pending
        }

        private fun invalidPending(): Nothing = throw IllegalArgumentException("Invalid browser sign-in request.")
    }
}

data class BrowserAuthorizationCode(val origin: String, val code: String, val verifier: String)

object BrowserAuth {
    const val REDIRECT_URI = "com.sskaraoke.player:/oauth/callback"
    const val LOGIN_LIFETIME_MS = 10 * 60 * 1000L
    private const val INVALID_LOGIN = "Invalid browser sign-in request."
    private const val INVALID_CALLBACK = "Invalid or expired browser sign-in callback."
    private val secretPattern = Regex("[A-Za-z0-9_-]{43}")
    private val hostnameLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    private val numericHost = Regex("(?:[0-9]+|0[xX][0-9a-fA-F]+)(?:\\.(?:[0-9]+|0[xX][0-9a-fA-F]+))*")
    private val random = SecureRandom()
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    fun begin(origin: String, now: Long = System.currentTimeMillis()): PendingBrowserLogin {
        require(now >= 0) { INVALID_LOGIN }
        val server = validatedOrigin(origin)
        fun randomSecret(): String = base64.encodeToString(ByteArray(32).also(random::nextBytes))
        return PendingBrowserLogin(server, randomSecret(), randomSecret(), now)
    }

    fun challenge(verifier: String): String {
        require(secretPattern.matches(verifier)) { INVALID_LOGIN }
        return base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }

    fun loginUrl(pending: PendingBrowserLogin): String {
        validatePending(pending)
        return pending.origin.toHttpUrlOrNull()!!.newBuilder().addPathSegment("app-login")
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("code_challenge", challenge(pending.verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", pending.state)
            .build().toString()
    }

    fun acceptCallback(
        rawUri: String,
        pending: PendingBrowserLogin?,
        now: Long = System.currentTimeMillis()
    ): BrowserAuthorizationCode {
        requireNotNull(pending) { INVALID_CALLBACK }
        validatePending(pending, now)
        require(rawUri.length <= 2048 && rawUri.startsWith("$REDIRECT_URI?")) { INVALID_CALLBACK }
        val uri = try { URI(rawUri) } catch (_: Exception) {
            throw IllegalArgumentException(INVALID_CALLBACK)
        }
        require(uri.scheme == "com.sskaraoke.player" && !uri.isOpaque && uri.rawAuthority == null &&
            uri.rawPath == "/oauth/callback" && uri.rawFragment == null) { INVALID_CALLBACK }
        val parameters = uri.rawQuery?.split('&') ?: throw IllegalArgumentException(INVALID_CALLBACK)
        require(parameters.size == 2) { INVALID_CALLBACK }
        val values = mutableMapOf<String, String>()
        for (parameter in parameters) {
            val pair = parameter.split('=', limit = 2)
            require(pair.size == 2) { INVALID_CALLBACK }
            val name = decode(pair[0])
            val value = decode(pair[1])
            require(name in setOf("code", "state") && !values.containsKey(name) &&
                secretPattern.matches(value)) { INVALID_CALLBACK }
            values[name] = value
        }
        val code = values["code"] ?: throw IllegalArgumentException(INVALID_CALLBACK)
        val state = values["state"] ?: throw IllegalArgumentException(INVALID_CALLBACK)
        require(MessageDigest.isEqual(
            state.toByteArray(Charsets.US_ASCII), pending.state.toByteArray(Charsets.US_ASCII)
        )) { INVALID_CALLBACK }
        return BrowserAuthorizationCode(pending.origin, code, pending.verifier)
    }

    internal fun validatedOrigin(origin: String): String {
        require(origin.length in 1..2048) { "Browser sign-in requires an HTTPS server hostname." }
        val uri = try { URI(origin) } catch (_: Exception) {
            throw IllegalArgumentException("Browser sign-in requires an HTTPS server hostname.")
        }
        val url = origin.toHttpUrlOrNull()
        require(uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
            uri.rawPath in setOf("", "/") && uri.rawQuery == null && uri.rawFragment == null &&
            uri.host != null && url != null && url.isHttps && url.host.length <= 253 &&
            url.host.split('.').all { hostnameLabel.matches(it) } && !numericHost.matches(url.host)) {
            "Browser sign-in requires an HTTPS server hostname."
        }
        return url.toString().removeSuffix("/")
    }

    internal fun validatePending(pending: PendingBrowserLogin, now: Long? = null) {
        require(validatedOrigin(pending.origin) == pending.origin &&
            secretPattern.matches(pending.verifier) && secretPattern.matches(pending.state) &&
            pending.createdAt >= 0) { INVALID_LOGIN }
        if (now != null) require(now >= pending.createdAt && now - pending.createdAt <= LOGIN_LIFETIME_MS) {
            INVALID_CALLBACK
        }
    }

    internal fun validateCode(code: String) {
        require(secretPattern.matches(code)) { INVALID_CALLBACK }
    }

    private fun decode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) {
        throw IllegalArgumentException(INVALID_CALLBACK)
    }
}
