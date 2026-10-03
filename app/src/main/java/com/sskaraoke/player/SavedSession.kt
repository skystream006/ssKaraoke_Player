package com.sskaraoke.player

import org.json.JSONObject

data class SavedSession(
    val origin: String = "",
    val token: String = "",
    val level: String = "none",
    val password: String = "",
    val kind: String = "password",
    val username: String = "",
    val usernameRequired: Boolean = false,
    val memberId: String = "",
    val memberName: String = "",
    val memberRole: String = "",
    val route: String = "/",
    val theme: String = "neonPurple",
    val view: String = "{}"
) {
    fun seed(): JSONObject = JSONObject()
        .put("token", token).put("level", level).put("username", username)
        .put("usernameRequired", usernameRequired).put("memberId", memberId)
        .put("memberName", memberName).put("memberRole", memberRole)
        .put("route", route).put("theme", theme).put("view", JSONObject(view))

    fun toJson(): JSONObject = seed().put("origin", origin).put("password", password).put("kind", kind)

    fun withSnapshot(state: JSONObject): SavedSession {
        val candidateToken = state.optString("token").takeIf { it.length <= 8192 }.orEmpty()
        val candidateLevel = state.optString("level").takeIf { it in setOf("member", "admin") } ?: "none"
        return copy(
            token = if (candidateLevel == "none") "" else candidateToken,
            level = if (candidateToken.isEmpty()) "none" else candidateLevel,
            username = state.optString("username").trim().takeIf { it.length <= 60 }.orEmpty(),
            usernameRequired = state.optBoolean("usernameRequired"),
            memberId = state.optString("memberId").take(100),
            memberName = state.optString("memberName").take(60),
            memberRole = state.optString("memberRole").takeIf { it in setOf("guest", "organizer") }.orEmpty(),
            route = if (origin.isBlank()) "/" else ServerAddress.parse(origin).safeRoute(state.optString("route", "/")),
            theme = state.optString("theme").takeIf { it in themes } ?: theme,
            view = state.optJSONObject("view")?.toString()?.takeIf { it.length <= 8192 } ?: "{}"
        )
    }

    fun signedOut(): SavedSession = SavedSession(origin = origin, theme = theme)

    fun switchUser(): SavedSession {
        if (token.isEmpty() || level !in setOf("member", "admin")) return this
        return copy(usernameRequired = true, memberId = "", memberName = "", memberRole = "", route = "/", view = "{}")
    }

    companion object {
        val themes = linkedMapOf(
            "neonPurple" to "Neon Purple", "ocean" to "Ocean", "forest" to "Forest",
            "sunset" to "Sunset", "midnightGold" to "Midnight Gold", "dark" to "Dark"
        )

        fun fromJson(json: JSONObject): SavedSession {
            val origin = json.optString("origin").takeIf { it.isNotEmpty() }?.let { ServerAddress.parse(it).origin }.orEmpty()
            return SavedSession(
                origin = origin,
                password = json.optString("password").take(4096),
                kind = if (json.optString("kind") == "qr") "qr" else "password"
            ).withSnapshot(json)
        }
    }
}