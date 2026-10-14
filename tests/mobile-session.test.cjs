const test = require("node:test");
const assert = require("node:assert/strict");
const { restoreSession, readSession, safeLocation, validUsername, swipeDirection } = require("../app/src/main/assets/mobile-session.js");

function storage(values = {}) {
    const entries = new Map(Object.entries(values));
    return {
        getItem: key => entries.get(key) ?? null,
        setItem: (key, value) => entries.set(key, String(value)),
        removeItem: key => entries.delete(key)
    };
}

test("a recreated WebView restores account and guest sessions before React starts", () => {
    for (const level of ["member", "admin", "hostscreen", "guest"]) {
        const session = storage();
        const local = storage();
        restoreSession(session, local, { token: "saved-token", level, username: "  Alex  " });
        assert.deepEqual(readSession(session, local), {
            token: "saved-token", level, username: "Alex",
            memberId: "", memberName: "", memberRole: ""
        });
        assert.equal(session.getItem("usernameRequired"), null);
    }
});

test("HOSTSCREEN keeps its authentication level separately from organizer membership and never saves the key", () => {
    const session = storage();
    const local = storage();
    const saved = {
        token: "hostscreen-session", level: "hostscreen", username: "HOSTSCREEN",
        memberId: "screen-member", memberName: "HOSTSCREEN", memberRole: "organizer"
    };
    restoreSession(session, local, { ...saved, key: "fixture-host-key" });
    assert.deepEqual(readSession(session, local), saved);
    assert.equal(session.getItem("key"), null);
    assert.equal(local.getItem("key"), null);
    assert.equal(safeLocation("/join", "https://karaoke.example"), "/join");
    assert.equal(safeLocation("/organizer/party-1/screen-member", "https://karaoke.example"), "/organizer/party-1/screen-member");
});

test("legacy username selection and password fields never return with a saved session", () => {
    const session = storage({
        authToken: "previous-token", authLevel: "admin", usernameRequired: "true",
        memberId: "old-member", memberName: "Previous member", memberRole: "organizer"
    });
    const local = storage();
    restoreSession(session, local, {
        token: "saved-token", level: "member", username: "Alex",
        usernameRequired: true, password: "obsolete-test-password"
    });
    assert.deepEqual(readSession(session, local), {
        token: "saved-token", level: "member", username: "Alex",
        memberId: "", memberName: "", memberRole: ""
    });
    for (const key of ["usernameRequired", "password", "memberId", "memberName", "memberRole"]) {
        assert.equal(session.getItem(key), null);
        assert.equal(local.getItem(key), null);
    }
});

test("missing, corrupt, and password-only checkpoints clear all stale authentication", () => {
    for (const saved of [
        undefined, null, {}, { password: "obsolete-test-password", level: "admin" },
        { token: "", level: "member" }, { token: "saved", level: "owner" },
        { token: "saved", level: "none" }, { token: 42, level: "admin" },
        { token: "x".repeat(8193), level: "guest" }
    ]) {
        const session = storage({
            authToken: "stale-token", authLevel: "admin", usernameRequired: "true",
            memberId: "old-member", memberName: "Alex", memberRole: "organizer"
        });
        const local = storage({ defaultUsername: "Alex", karaokeTheme: "ocean" });
        restoreSession(session, local, saved);
        for (const key of ["authToken", "authLevel", "usernameRequired", "memberId", "memberName", "memberRole"]) {
            assert.equal(session.getItem(key), null, `${key} survived ${JSON.stringify(saved)}`);
        }
        assert.deepEqual(readSession(session, local), {
            token: "", level: "none", username: "Alex", memberId: "", memberName: "", memberRole: ""
        });
        assert.equal(local.getItem("karaokeTheme"), "ocean");
    }
});

test("guest invitation sessions preserve their party member identity across recreation", () => {
    const session = storage({ usernameRequired: "true" });
    const local = storage();
    const saved = {
        token: "invitation-session", level: "guest", username: "Alex",
        memberId: "abc-123", memberName: "Alex", memberRole: "guest"
    };
    restoreSession(session, local, saved);
    assert.deepEqual(readSession(session, local), saved);
    const recreated = storage();
    restoreSession(recreated, storage(), readSession(session, local));
    assert.equal(recreated.getItem("authToken"), saved.token);
    assert.equal(recreated.getItem("authLevel"), "guest");
    assert.equal(recreated.getItem("memberId"), saved.memberId);
    assert.equal(recreated.getItem("usernameRequired"), null);
});

test("only known same-origin karaoke locations are persisted", () => {
    const origin = "https://karaoke.example:8443";
    assert.equal(safeLocation("/guest/123/456", origin), "/guest/123/456");
    assert.equal(safeLocation("/organizer/123/456?token=secret#fragment", origin), "/organizer/123/456");
    assert.equal(safeLocation("/join/AB12CD", origin), "/join/AB12CD");
    assert.equal(safeLocation("/account", origin), "/account");
    assert.equal(safeLocation("/account/?token=secret#fragment", origin), "/account/");
    assert.equal(safeLocation(`${origin}/account`, origin), "/account");
    assert.equal(safeLocation("https://other.example/guest/1/2", origin), "/");
    assert.equal(safeLocation("//other.example/account", origin), "/");
    const credentialed = new URL("/settings", origin);
    credentialed.username = "someone";
    assert.equal(safeLocation(credentialed.href, origin), "/");
    assert.equal(safeLocation("javascript:alert(1)", origin), "/");
    assert.equal(safeLocation("/unknown", origin), "/");
    assert.equal(safeLocation("/account/private", origin), "/");
});

test("only canonical invitation URLs retain their 43-character base64url fragment", () => {
    const origin = "https://karaoke.example:8443";
    const invitation = "a".repeat(41) + "_-";
    const route = `/join/invite#invite=${invitation}`;
    assert.equal(safeLocation(route, origin), route);
    assert.equal(safeLocation(origin + route, origin), route);
    for (const hash of [
        "#invite=", `#invite=${invitation.slice(1)}`, `#invite=${invitation}a`,
        `#invite=${invitation}=`, `#invite=${"a".repeat(42)}+`, `#invite=${"a".repeat(42)}/`,
        `#invite=${"a".repeat(42)}%41`, `#invite=${invitation}&extra=value`,
        `#invite=${invitation}#extra`, `#Invite=${invitation}`, "#fragment"
    ]) {
        assert.equal(safeLocation(`/join/invite${hash}`, origin), "/join/invite", hash);
    }
    assert.equal(safeLocation(`/join/invite?token=secret#invite=${invitation}`, origin), "/join/invite");
    assert.equal(safeLocation(`/join/invite/#invite=${invitation}`, origin), "/join/invite/");
    assert.equal(safeLocation(`/Join/invite#invite=${invitation}`, origin), "/Join/invite");
    assert.equal(safeLocation(`/account#invite=${invitation}`, origin), "/account");
    assert.equal(safeLocation(`/join/AB12CD#invite=${invitation}`, origin), "/join/AB12CD");
    assert.equal(safeLocation(`https://other.example${route}`, origin), "/");
});

test("returning party members retain their identity and role", () => {
    const session = storage();
    restoreSession(session, storage(), {
        token: "saved", level: "member", memberId: "abc-123", memberName: "Alex", memberRole: "organizer"
    });
    assert.equal(session.getItem("memberName"), "Alex");
    assert.equal(session.getItem("memberId"), "abc-123");
    assert.equal(session.getItem("memberRole"), "organizer");
});

test("corrupt tokens never preserve an authenticated level", () => {
    for (const values of [
        { authToken: "x".repeat(8193), authLevel: "admin" },
        { authToken: "", authLevel: "guest" },
        { authToken: "token", authLevel: "owner" },
        { authToken: "token" }
    ]) {
        const session = readSession(storage(values), storage());
        assert.equal(session.token, "");
        assert.equal(session.level, "none");
    }
    assert.equal(readSession(storage({ authToken: "x".repeat(8192), authLevel: "guest" }), storage()).level, "guest");
});

test("invalid default usernames are not restored or included in snapshots", () => {
    for (const username of ["", "   ", "x".repeat(61), null, 42]) {
        assert.equal(validUsername(username), false);
        const local = storage();
        restoreSession(storage(), local, { token: "saved-token", level: "member", username });
        assert.equal(local.getItem("defaultUsername"), null);
    }
    assert.equal(readSession(storage(), storage({ defaultUsername: "x".repeat(61) })).username, "");
    assert.equal(validUsername("  Alex  "), true);
    assert.equal(validUsername("x".repeat(60)), true);
});

test("swipes must be deliberate horizontal movements, not scrolling or long-press dragging", () => {
    const start = { x: 200, y: 200, time: 0 };
    assert.equal(swipeDirection(start, { x: 80, y: 210, time: 250 }), 1);
    assert.equal(swipeDirection(start, { x: 310, y: 210, time: 250 }), -1);
    assert.equal(swipeDirection(start, { x: 190, y: 350, time: 250 }), 0);
    assert.equal(swipeDirection(start, { x: 80, y: 210, time: 850 }), 0);
    assert.equal(swipeDirection(start, { x: 185, y: 200, time: 100 }), 0);
});
