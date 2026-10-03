(function (root) {
    "use strict";

    function validUsername(value) {
        return typeof value === "string" && value.trim().length > 0 && value.trim().length <= 60;
    }

    function safeLocation(value, origin) {
        try {
            const address = new URL(value, origin);
            if (address.origin !== new URL(origin).origin || address.username || address.password) return "/";
            const path = address.pathname;
            return /^(\/|\/settings|\/join(?:\/[a-z0-9]+)?|\/(?:organizer|guest)\/[a-z0-9-]+\/[a-z0-9-]+)\/?$/i.test(path)
                ? path : "/";
        } catch (_) {
            return "/";
        }
    }

    function readSession(session, local) {
        const token = session.getItem("authToken") || "";
        const level = session.getItem("authLevel") || "none";
        const username = (local.getItem("defaultUsername") || "").trim();
        const valid = token.length > 0 && token.length <= 8192 && ["member", "admin"].includes(level);
        return {
            token: valid ? token : "",
            level: valid ? level : "none",
            username: validUsername(username) ? username : "",
            usernameRequired: session.getItem("usernameRequired") === "true",
            memberId: (session.getItem("memberId") || "").slice(0, 100),
            memberName: (session.getItem("memberName") || "").slice(0, 60),
            memberRole: ["guest", "organizer"].includes(session.getItem("memberRole")) ? session.getItem("memberRole") : ""
        };
    }

    function restoreSession(session, local, saved) {
        if (!saved || typeof saved.token !== "string" || !saved.token || saved.token.length > 8192 || !["member", "admin"].includes(saved.level)) return;
        session.setItem("authToken", saved.token);
        session.setItem("authLevel", saved.level);
        for (const key of ["memberId", "memberName", "memberRole"]) {
            if (typeof saved[key] === "string" && saved[key]) session.setItem(key, saved[key]);
        }
        if (validUsername(saved.username)) {
            local.setItem("defaultUsername", saved.username.trim());
            session.removeItem("usernameRequired");
        } else if (saved.usernameRequired) {
            session.setItem("usernameRequired", "true");
        } else {
            session.removeItem("usernameRequired");
        }
    }

    function swipeDirection(start, end) {
        const horizontal = end.x - start.x;
        const vertical = end.y - start.y;
        if (end.time - start.time > 650 || end.time - start.time < 35) return 0;
        if (Math.abs(horizontal) < 72 || Math.abs(vertical) > 48 || Math.abs(horizontal) < Math.abs(vertical) * 1.8) return 0;
        return horizontal < 0 ? 1 : -1;
    }

    const api = { validUsername, safeLocation, readSession, restoreSession, swipeDirection };
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    else root.KaraokeMobileSession = api;
})(typeof window !== "undefined" ? window : globalThis);