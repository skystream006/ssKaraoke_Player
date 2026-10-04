const { test: base, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");

const asset = name => fs.readFileSync(path.resolve(__dirname, "../../app/src/main/assets", name), "utf8");
const themes = JSON.parse(asset("color-themes.json"));
const partyId = "11111111-1111-4111-8111-111111111111";
const memberId = "22222222-2222-4222-8222-222222222222";
const route = `/guest/${partyId}/${memberId}`;
const seed = {
    token: "test-session", level: "member", username: "Alex", usernameRequired: false,
    memberId, memberName: "Alex", memberRole: "guest", route, theme: "ocean",
    view: { tab: "queue", scroll: {} }
};
const test = base.extend({ savedSession: [seed, { option: true }] });
const queue = ["City Lights", "Summer Nights", "Second Verse"].map((title, index) => ({
    id: `33333333-3333-4333-8333-33333333333${index}`, video_id: `video-${index}`, video_title: title,
    video_thumbnail: "/test-icon.png", singer_name: "Alex", position: index + 1, status: "queued"
}));

test.beforeEach(async ({ context, page, savedSession }) => {
    await context.addInitScript({ content: `if(window===window.top){
        window.nativeEvents=[];
        const writeStorage = Storage.prototype.setItem;
        window.KaraokeHost={postMessage:message=>{
            const event=JSON.parse(message);
            window.nativeEvents.push(event);
            if(event.type==='snapshot') writeStorage.call(sessionStorage,'native-test-checkpoint',JSON.stringify(event.state));
        }};
        window.__karaokeSeed=JSON.parse(sessionStorage.getItem('native-test-checkpoint') || ${JSON.stringify(JSON.stringify(savedSession))});
        window.__karaokeCss=${JSON.stringify(asset("mobile-shell.css"))};
        window.__karaokeThemes=${JSON.stringify(themes)};
        ${asset("mobile-session.js")}
        ${asset("mobile-shell.js")}
    }` });
    await page.route("**/api/**", async interception => {
        const request = interception.request();
        const url = new URL(request.url());
        let body = {};
        let status = 200;
        if (url.pathname === "/api/auth") {
            const password = request.postDataJSON()?.password;
            status = password === "wrong" ? 401 : 200;
            body = status === 401 ? { error: "Invalid password" } : { token: "authenticated-token", level: "member" };
        } else if (url.pathname.startsWith("/api/queue/")) {
            body = queue;
        } else if (url.pathname === "/api/parties/members/names") {
            body = [{ name: "Alex" }, { name: "Blair" }];
        } else if (url.pathname.includes("/members/")) {
            body = { id: memberId, name: "Alex", role: "guest" };
        } else if (url.pathname === "/api/parties") {
            body = [{ id: partyId, name: "Friday Karaoke", join_code: "ABC123", is_active: true, is_locked: false }];
        } else {
            body = { id: partyId, name: "Friday Karaoke", join_code: "ABC123", is_active: true, is_locked: false };
        }
        await interception.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    });
    await page.goto(savedSession.route);
    if (savedSession.usernameRequired) await expect(page.getByLabel("Default username")).toBeVisible();
    else await expect(page.locator(".guest-layout")).toBeVisible();
});

test.describe("switch user", () => {
    test.use({ savedSession: { ...seed, usernameRequired: true, memberId: "", memberName: "", memberRole: "", route: "/", view: {} } });

    test("changes and remembers the default username without password login or reusing the previous member", async ({ page }) => {
        const mutations = [];
        page.on("request", request => {
            if (request.url().includes("/api/") && request.method() !== "GET") mutations.push(request.url());
        });
        await expect(page.getByLabel("Default username")).toHaveValue("Alex");
        await expect(page.locator('input[type="password"]')).toHaveCount(0);
        await page.reload();
        await expect(page.getByLabel("Default username")).toHaveValue("Alex");
        if (process.env.KARAOKE_FRONTEND_BUILD) {
            await expect(page.locator('#default-username-options option[value="Blair"]')).toHaveCount(1);
        }
        await page.getByLabel("Default username").fill("Blair");
        await page.getByRole("button", { name: "Continue", exact: true }).click();
        await expect(page.getByLabel("Default username")).toHaveCount(0);
        await expect.poll(() => page.evaluate(() => JSON.parse(sessionStorage.getItem("native-test-checkpoint"))?.username)).toBe("Blair");
        await page.reload();
        await expect(page.locator(".password-modal")).toHaveCount(0);
        const restored = await page.evaluate(() => window.KaraokeMobileSession.readSession(sessionStorage, localStorage));
        expect(restored).toEqual({ token: "test-session", level: "member", username: "Blair", usernameRequired: false, memberId: "", memberName: "", memberRole: "" });
        expect(mutations).toEqual([]);
    });
});

test("restores login, member identity, theme, and the selected tab before continuing", async ({ page }, testInfo) => {
    expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBe("test-session");
    expect(await page.evaluate(() => sessionStorage.getItem("usernameRequired"))).toBeNull();
    await expect(page.locator(".member-badge")).toContainText("Alex");
    await expect(page.locator(".mobile-tab.active")).toContainText("Queue");
    await expect(page.locator(".theme-picker")).toBeHidden();
    expect(await page.evaluate(() => localStorage.getItem("karaokeTheme"))).toBe("ocean");
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--primary").trim())).toBe(themes.ocean["--primary"]);
    await expect(page.locator("body")).toHaveCSS("background-color", "rgb(0, 18, 25)");
    await page.screenshot({ path: testInfo.outputPath("restored-guest.png"), fullPage: true });
});

test("removes the user top bar without hiding the party header or reserving its space", async ({ page }) => {
    const toolbar = page.locator(".user-toolbar");
    await expect(toolbar).toHaveCount(1);
    await expect(toolbar.locator(".user-toolbar__name")).toHaveText("Alex");
    await expect(toolbar).toBeHidden();
    await expect(page.getByRole("button", { name: "Switch user", exact: true })).toHaveCount(0);
    await expect(page.locator(".guest-header")).toBeVisible();
    await expect(page.locator(".member-badge")).toBeVisible();
    await expect(page.locator(".mobile-tab.active")).toContainText("Queue");
    expect((await page.locator(".guest-layout").boundingBox()).y).toBe(0);

    await page.reload();
    await expect(toolbar).toBeHidden();
    await expect(page.locator(".guest-header")).toBeVisible();

    await page.evaluate(() => delete document.documentElement.dataset.sskaraokeAndroid);
    await expect(toolbar).toBeVisible();
    await expect(page.getByRole("button", { name: "Switch user", exact: true })).toBeVisible();
    expect((await page.locator(".guest-layout").boundingBox()).y).toBeGreaterThan(0);
});

test("saves only successful fetch and XMLHttpRequest passwords", async ({ page }) => {
    await page.evaluate(async () => {
        await fetch("/api/auth", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password: "wrong" }) });
        await fetch("/api/auth", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password: "fetch-test-password" }) });
        await new Promise(resolve => {
            const request = new XMLHttpRequest();
            request.open("POST", "/api/auth");
            request.setRequestHeader("Content-Type", "application/json");
            request.onloadend = resolve;
            request.send(JSON.stringify({ password: "xhr-test-password" }));
        });
    });
    await expect.poll(() => page.evaluate(() => window.nativeEvents.filter(event => event.type === "credentials").length)).toBe(2);
    const credentials = await page.evaluate(() => window.nativeEvents.filter(event => event.type === "credentials"));
    expect(credentials.map(event => event.password)).toEqual(["fetch-test-password", "xhr-test-password"]);
});

test("auth expiry is signaled once without replaying the failed mutation", async ({ page }) => {
    let attempts = 0;
    await page.route("**/api/expired-action", async interception => {
        attempts++;
        await interception.fulfill({ status: 401, contentType: "application/json", body: "{}" });
    });
    await page.evaluate(async () => {
        await fetch("/api/expired-action", { method: "POST" });
        await fetch("/api/expired-action", { method: "POST" });
    });
    await expect.poll(() => page.evaluate(() => window.nativeEvents.filter(event => event.type === "expired").length)).toBe(1);
    expect(attempts).toBe(2);
});

test("a horizontal swipe switches tabs while a drag-handle swipe does not", async ({ page }) => {
    await page.clock.install();
    await page.evaluate(() => document.querySelectorAll(".mobile-tab")[1].click());
    const swipe = async selector => {
        await page.evaluate(selector => {
            window.testTouchTarget = document.querySelector(selector);
            const touch = new Touch({ identifier: 1, target: window.testTouchTarget, clientX: 300, clientY: 320 });
            window.testTouchTarget.dispatchEvent(new TouchEvent("touchstart", { bubbles: true, touches: [touch], changedTouches: [touch] }));
        }, selector);
        await page.clock.fastForward(180);
        await page.evaluate(() => {
            const touch = new Touch({ identifier: 1, target: window.testTouchTarget, clientX: 120, clientY: 325 });
            window.testTouchTarget.dispatchEvent(new TouchEvent("touchend", { bubbles: true, touches: [], changedTouches: [touch] }));
        });
    };
    await swipe(".drag-handle");
    await expect(page.locator(".mobile-tab.active")).toContainText("Queue");
    await page.clock.runFor(500);
    await expect(page.locator(".playlist-item.dragging")).toHaveCount(0);
    await swipe(".queue-section");
    await expect(page.locator(".mobile-tab.active")).toContainText("Status");
    await page.clock.resume();
});

test("all theme colors update in place and survive reloads", async ({ page }) => {
    await page.evaluate(() => {
        window.renderMarker = "same-document";
    });
    for (const [key, palette] of Object.entries(themes)) {
        await page.evaluate(key => window.KaraokeAndroid.applyTheme(key), key);
        expect(await page.evaluate(() => localStorage.getItem("karaokeTheme"))).toBe(key);
        const applied = await page.evaluate(properties => {
            const style = getComputedStyle(document.documentElement);
            return Object.fromEntries(properties.map(property => [property, style.getPropertyValue(property).trim()]));
        }, Object.keys(palette));
        expect(applied).toEqual(palette);
        expect(await page.evaluate(() => window.renderMarker)).toBe("same-document");
        await expect.poll(() => page.evaluate(() => JSON.parse(sessionStorage.getItem("native-test-checkpoint"))?.theme)).toBe(key);
    }
    await page.evaluate(() => window.KaraokeAndroid.applyTheme("__proto__"));
    expect(await page.evaluate(() => localStorage.getItem("karaokeTheme"))).toBe("dark");
    await page.reload();
    await expect(page.locator(".mobile-tab.active")).toContainText("Queue");
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--primary").trim())).toBe(themes.dark["--primary"]);
    await expect(page.locator("body")).toHaveCSS("background-color", "rgb(10, 10, 13)");
});

test("real server playlist reorders with a touch long-press and calls the reorder endpoint", async ({ page, context }) => {
    test.skip(!process.env.KARAOKE_FRONTEND_BUILD, "Requires the local karaoke frontend build to exercise its existing drag library.");
    await page.evaluate(() => document.querySelectorAll(".mobile-tab")[1].click());
    const handles = page.locator(".drag-handle");
    await expect(handles).toHaveCount(3);
    await handles.first().scrollIntoViewIfNeeded();
    const first = await handles.first().boundingBox();
    const last = await handles.last().boundingBox();
    const session = await context.newCDPSession(page);
    const start = { x: first.x + first.width / 2, y: first.y + first.height / 2 };
    const end = { x: last.x + last.width / 2, y: last.y + last.height / 2 };
    await session.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ ...start, id: 1 }] });
    await expect(page.locator(".playlist-item.dragging")).toHaveCount(1);
    for (const fraction of [0.2, 0.4, 0.6, 0.8, 1]) {
        await session.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x: start.x, y: start.y + (end.y - start.y) * fraction, id: 1 }] });
    }
    const reordered = page.waitForRequest(request => request.url().endsWith(`/api/queue/${partyId}/reorder`) && request.method() === "PUT");
    await session.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
    const request = await reordered;
    expect(JSON.stringify(request.postDataJSON())).toContain(queue[0].id);
    await expect(page.locator(".playlist-item").last()).toContainText("City Lights");
});