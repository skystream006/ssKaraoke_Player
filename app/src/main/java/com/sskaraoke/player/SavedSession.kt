package com.sskaraoke.player

import org.json.JSONObject

data class SavedSession(
    val origin: String = "",
    val token: String = "",
    val level: String = "none",
    val username: String = "",
    val memberId: String = "",
    val memberName: String = "",
    val memberRole: String = "",
    val route: String = "/",
    val theme: String = "neonPurple",
    val view: String = "{}",
    val pendingLogin: PendingBrowserLogin? = null
) {
    fun seed(): JSONObject = JSONObject()
        .put("token", token).put("level", level).put("username", username)
        .put("memberId", memberId)
        .put("memberName", memberName).put("memberRole", memberRole)
        .put("route", route).put("theme", theme).put("view", JSONObject(view))

    fun toJson(): JSONObject = seed().put("origin", origin).apply {
        pendingLogin?.let { put("pendingLogin", it.toJson()) }
    }

    fun withSnapshot(state: JSONObject): SavedSession {
        val candidateToken = state.optString("token").takeIf { it.length <= 8192 }.orEmpty()
        val candidateLevel = state.optString("level").takeIf { it in levels } ?: "none"
        return copy(
            token = if (candidateLevel == "none") "" else candidateToken,
            level = if (candidateToken.isEmpty()) "none" else candidateLevel,
            username = state.optString("username").trim().takeIf { it.length <= 60 }.orEmpty(),
            memberId = state.optString("memberId").take(100),
            memberName = state.optString("memberName").take(60),
            memberRole = state.optString("memberRole").takeIf { it in setOf("guest", "organizer") }.orEmpty(),
            route = if (origin.isBlank()) "/" else ServerAddress.parse(origin).safeRoute(state.optString("route", "/")),
            theme = state.optString("theme").takeIf { it in themes } ?: theme,
            view = state.optJSONObject("view")?.toString()?.takeIf { it.length <= 8192 } ?: "{}"
        )
    }

    fun signedOut(): SavedSession = SavedSession(origin = origin, theme = theme)

    companion object {
        val levels = setOf("member", "admin", "hostscreen", "guest")
        val themes = linkedMapOf(
            "neonPurple" to "Neon Purple", "ocean" to "Ocean", "forest" to "Forest",
            "sunset" to "Sunset", "midnightGold" to "Midnight Gold", "dark" to "Dark"
        )

        fun fromJson(json: JSONObject): SavedSession {
            val origin = json.optString("origin").takeIf { it.isNotEmpty() }?.let { ServerAddress.parse(it).origin }.orEmpty()
            val saved = SavedSession(origin = origin).withSnapshot(json)
            val pending = if (saved.token.isEmpty() && saved.level == "none" && json.optString("token").isEmpty()) {
                json.optJSONObject("pendingLogin")?.let {
                    try {
                        PendingBrowserLogin.fromJson(it).takeIf { login -> login.origin == origin }
                    } catch (_: IllegalArgumentException) { null }
                }
            } else null
            return saved.copy(pendingLogin = pending)
        }
    }
}