package com.sskaraoke.player

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ServerAddress private constructor(val url: HttpUrl, val initialRoute: String) {
    val origin: String get() = url.toString().removeSuffix("/")
    val encrypted: Boolean get() = url.isHttps

    fun api(path: String): HttpUrl = url.newBuilder().addPathSegment("api").addPathSegments(path).build()

    fun owns(address: String): Boolean {
        val candidate = address.toHttpUrlOrNull() ?: return false
        return candidate.scheme == url.scheme && candidate.host == url.host && candidate.port == url.port &&
            candidate.username.isEmpty() && candidate.password.isEmpty()
    }

    fun safeRoute(address: String): String {
        val candidate = url.resolve(address) ?: return "/"
        return if (owns(candidate.toString()) && routePattern.matches(candidate.encodedPath)) candidate.encodedPath else "/"
    }

    companion object {
        private val routePattern = Regex("(?:/|/settings|/join(?:/[a-zA-Z0-9]+)?|/(?:organizer|guest)/[a-zA-Z0-9-]+/[a-zA-Z0-9-]+)/?")

        fun parse(input: String): ServerAddress {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() || it == '\\' }) { "Enter a server address." }
            val address = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter an HTTP or HTTPS server address.")
            require(address.username.isEmpty() && address.password.isEmpty()) { "Do not include credentials in the address." }
            require(address.query == null && address.fragment == null) { "Remove the query or fragment from the address." }
            require(address.encodedPath == "/" || Regex("/join/[a-zA-Z0-9]+/?").matches(address.encodedPath)) {
                "Use the server's root address or a party join link, not its /api address."
            }
            return ServerAddress(address.newBuilder().encodedPath("/").build(), address.encodedPath)
        }
    }
}