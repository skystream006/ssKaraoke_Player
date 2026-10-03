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

test("a recreated WebView restores login and the chosen username before React starts", () => {
    const session = storage();
    const local = storage();
    restoreSession(session, local, { token: "saved-token", level: "member", username: "  Alex  ", usernameRequired: false });
    assert.deepEqual(readSession(session, local), {
        token: "saved-token", level: "member", username: "Alex", usernameRequired: false,
        memberId: "", memberName: "", memberRole: ""
    });
});

test("an unfinished password login still requires a username", () => {
    const session = storage();
    restoreSession(session, storage(), { token: "token", level: "admin", username: "", usernameRequired: true });
    assert.equal(session.getItem("usernameRequired"), "true");
});

test("switching users preserves authentication and prefills the old name without skipping username selection", () => {
    const session = storage({ memberId: "old-member", memberName: "Alex", memberRole: "organizer" });
    const local = storage();
    restoreSession(session, local, {
        token: "saved-token", level: "admin", username: "Alex", usernameRequired: true,
        memberId: "", memberName: "", memberRole: ""
    });
    assert.deepEqual(readSession(session, local), {
        token: "saved-token", level: "admin", username: "Alex", usernameRequired: true,
        memberId: "", memberName: "", memberRole: ""
    });
});

test("QR sessions do not gain an extra username requirement", () => {
    const session = storage({ usernameRequired: "true" });
    restoreSession(session, storage(), { token: "qr-token", level: "member", usernameRequired: false });
    assert.equal(session.getItem("usernameRequired"), null);
});

test("invalid session roles cannot restore authentication", () => {
    const session = storage();
    restoreSession(session, storage(), { token: "token", level: "owner" });
    assert.equal(session.getItem("authToken"), null);
    assert.equal(validUsername("x".repeat(61)), false);
});

test("only known same-origin karaoke locations are persisted", () => {
    const origin = "https://karaoke.example:8443";
    assert.equal(safeLocation("/guest/123/456", origin), "/guest/123/456");
    assert.equal(safeLocation("/organizer/123/456?token=secret#fragment", origin), "/organizer/123/456");
    assert.equal(safeLocation("/join/AB12CD", origin), "/join/AB12CD");
    assert.equal(safeLocation("https://other.example/guest/1/2", origin), "/");
    assert.equal(safeLocation("https://user:password@karaoke.example:8443/settings", origin), "/");
    assert.equal(safeLocation("javascript:alert(1)", origin), "/");
    assert.equal(safeLocation("/unknown", origin), "/");
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
    assert.equal(readSession(storage({ authToken: "x".repeat(8193), authLevel: "admin" }), storage()).level, "none");
    const session = storage();
    restoreSession(session, storage(), { token: 42, level: "admin" });
    assert.equal(session.getItem("authToken"), null);
});

test("swipes must be deliberate horizontal movements, not scrolling or long-press dragging", () => {
    const start = { x: 200, y: 200, time: 0 };
    assert.equal(swipeDirection(start, { x: 80, y: 210, time: 250 }), 1);
    assert.equal(swipeDirection(start, { x: 310, y: 210, time: 250 }), -1);
    assert.equal(swipeDirection(start, { x: 190, y: 350, time: 250 }), 0);
    assert.equal(swipeDirection(start, { x: 80, y: 210, time: 850 }), 0);
    assert.equal(swipeDirection(start, { x: 185, y: 200, time: 100 }), 0);
});