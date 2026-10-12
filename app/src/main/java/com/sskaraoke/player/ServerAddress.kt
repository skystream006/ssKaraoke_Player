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
        if (!owns(candidate.toString()) || !routePattern.matches(candidate.encodedPath)) return "/"
        return candidate.encodedPath + if (candidate.encodedPath == "/join/invite" &&
            candidate.query == null && invitationFragment.matches(candidate.encodedFragment.orEmpty())) {
            "#${candidate.encodedFragment}"
        } else ""
    }

    companion object {
        private val routePattern = Regex("(?:/|/account|/settings|/join(?:/[a-zA-Z0-9]+)?|/(?:organizer|guest)/[a-zA-Z0-9-]+/[a-zA-Z0-9-]+)/?")
        private val invitationFragment = Regex("invite=[A-Za-z0-9_-]{43}")

        fun parse(input: String): ServerAddress {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() || it == '\\' }) { "Enter a server address." }
            val address = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter an HTTP or HTTPS server address.")
            require(address.username.isEmpty() && address.password.isEmpty()) { "Do not include credentials in the address." }
            require(address.query == null && (address.fragment == null ||
                address.encodedPath == "/join/invite" && invitationFragment.matches(address.encodedFragment.orEmpty()))) {
                "Use a server address or a current host invitation link."
            }
            require(address.encodedPath == "/" || Regex("/join/[a-zA-Z0-9]+/?").matches(address.encodedPath)) {
                "Use the server's root address or a party join link, not its /api address."
            }
            val server = ServerAddress(address.newBuilder().encodedPath("/").fragment(null).build(), "/")
            return ServerAddress(server.url, server.safeRoute(address.toString()))
        }
    }
}