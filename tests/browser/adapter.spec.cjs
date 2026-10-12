const { test: base, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");

const asset = name => fs.readFileSync(path.resolve(__dirname, "../../app/src/main/assets", name), "utf8");
const themes = JSON.parse(asset("color-themes.json"));
const partyId = "11111111-1111-4111-8111-111111111111";
const memberId = "22222222-2222-4222-8222-222222222222";
const route = `/guest/${partyId}/${memberId}`;
const seed = {
    token: "test-session", level: "member", username: "Alex",
    memberId, memberName: "Alex", memberRole: "guest", route, theme: "ocean",
    view: { tab: "queue", scroll: {} }
};
const signedOut = { ...seed, token: "", level: "none", username: "", memberId: "", memberName: "", memberRole: "", route: "/", view: {} };
const user = { id: "44444444-4444-4444-8444-444444444444", name: "Alex", role: "member", status: "approved" };
const passkeySessions = {
    register: { token: "register-session", level: "admin", user: { ...user, name: "Blair", role: "admin" } },
    login: { token: "login-session", level: "member", user: { ...user, name: "Blair" } }
};
const guestSession = {
    token: "invitation-session", level: "guest", user: null, partyId, memberId,
    member: { id: memberId, name: "Invited guest", role: "guest" }
};
const invitation = "a".repeat(41) + "_-";
const test = base.extend({
    savedSession: [seed, { option: true }],
    startupSession: [null, { option: true }]
});
const queue = ["City Lights", "Summer Nights", "Second Verse"].map((title, index) => ({
    id: `33333333-3333-4333-8333-33333333333${index}`, video_id: `video-${index}`, video_title: title,
    video_thumbnail: "/test-icon.png", singer_name: "Alex", position: index + 1, status: "queued"
}));

const checkpoint = page => page.evaluate(() => JSON.parse(sessionStorage.getItem("native-test-checkpoint")));
const authEvents = page => page.evaluate(() => window.nativeEvents.filter(event => ["logout", "unavailable", "expired", "credentials"].includes(event.type)));
async function recreateWebView(page) {
    await page.evaluate(() => window.recreateWebView());
    await page.reload();
}

test.beforeEach(async ({ context, page, savedSession, startupSession }) => {
    await context.addInitScript({ content: `if(window===window.top){
        window.nativeEvents=[];
        const writeStorage = Storage.prototype.setItem;
        const clearStorage = Storage.prototype.clear;
        window.KaraokeHost={postMessage:message=>{
            if(window.discardingWebView) return;
            const event=JSON.parse(message);
            window.nativeEvents.push(event);
            if(event.type==='snapshot') writeStorage.call(sessionStorage,'native-test-checkpoint',JSON.stringify(event.state));
            if(event.type==='logout') {
                const previous=JSON.parse(sessionStorage.getItem('native-test-checkpoint') || '{}');
                writeStorage.call(sessionStorage,'native-test-checkpoint',JSON.stringify({
                    ...previous, token:'', level:'none', memberId:'', memberName:'', memberRole:'', route:'/', view:{}
                }));
            }
        }};
        window.recreateWebView=()=>{
            const saved=sessionStorage.getItem('native-test-checkpoint');
            window.discardingWebView=true;
            clearStorage.call(sessionStorage);
            clearStorage.call(localStorage);
            if(saved) writeStorage.call(sessionStorage,'native-test-checkpoint',saved);
        };
        window.__karaokeSeed=JSON.parse(sessionStorage.getItem('native-test-checkpoint') || ${JSON.stringify(JSON.stringify(savedSession))});
        window.__karaokeCss=${JSON.stringify(asset("mobile-shell.css"))};
        window.__karaokeThemes=${JSON.stringify(themes)};
        ${asset("mobile-session.js")}
        ${asset("mobile-shell.js")}
        const startup=${JSON.stringify(startupSession)};
        if(startup) {
            sessionStorage.setItem('authToken',startup.token);
            sessionStorage.setItem('authLevel',startup.level);
            localStorage.setItem('defaultUsername',startup.user.name);
        }
        window.startupSnapshots=window.nativeEvents.filter(event=>event.type==='snapshot');
    }` });
    await page.route("**/api/**", async interception => {
        const request = interception.request();
        const url = new URL(request.url());
        let body = {};
        let status = 200;
        if (url.pathname === "/api/auth/me") {
            const token = request.headers().authorization?.split(" ")[1];
            const session = [...Object.values(passkeySessions), guestSession, startupSession].filter(Boolean).find(value => value.token === token);
            body = session ? { ...session } : { level: savedSession.level, user: { ...user, name: savedSession.username || "Alex" } };
            delete body.token;
        } else if (/^\/api\/auth\/(register|login|passkeys)\/options$/.test(url.pathname)) {
            body = { requestId: "fixture-request", options: { challenge: "fixture-challenge" } };
        } else if (/^\/api\/auth\/(register|login)\/verify$/.test(url.pathname)) {
            body = passkeySessions[url.pathname.split("/")[3]];
        } else if (url.pathname === "/api/auth/logout" || url.pathname === "/api/auth/passkeys/verify") {
            body = { ok: true };
        } else if (url.pathname === "/api/auth/passkeys" || url.pathname === "/api/auth/users") {
            body = [];
        } else if (url.pathname === "/api/parties/invitations/resolve") {
            body = { party: { id: partyId, name: "Friday Karaoke" } };
        } else if (url.pathname === `/api/parties/${partyId}/join`) {
            body = guestSession;
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
    if (!savedSession.token && !startupSession) await expect(page.locator(".passkey-panel")).toBeVisible();
    else if (savedSession.route === "/account") await expect(page.locator(".account-page")).toBeVisible();
    else await expect(page.locator(".guest-layout")).toBeVisible();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.evaluate(() => window.KaraokeAndroid.snapshot());
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

test("keeps sign-out in the party header without adding a user top bar", async ({ page }) => {
    await expect(page.locator(".user-toolbar")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
    await expect(page.locator(".guest-header")).toBeVisible();
    await expect(page.locator(".member-badge")).toBeVisible();
    await expect(page.locator(".mobile-tab.active")).toContainText("Queue");
    expect((await page.locator(".guest-layout").boundingBox()).y).toBe(0);

    await page.reload();
    await expect(page.locator(".user-toolbar")).toHaveCount(0);
    await expect(page.locator(".guest-header")).toBeVisible();
    await expect(page.locator(".guest-header").getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
    expect((await page.locator(".guest-layout").boundingBox()).y).toBe(0);
});

test("generic 401 responses do not expire a session or replay the failed mutation", async ({ page }) => {
    let attempts = 0;
    await page.route("**/api/expired-action", async interception => {
        attempts++;
        await interception.fulfill({ status: 401, contentType: "application/json", body: "{}" });
    });
    await page.evaluate(async () => {
        await fetch("/api/expired-action", { method: "POST" });
        await fetch("/api/expired-action", { method: "POST" });
    });
    expect(await authEvents(page)).toEqual([]);
    expect((await checkpoint(page)).token).toBe(seed.token);
    expect(attempts).toBe(2);
});

test("the server auth:expired event clears the checkpoint without resurrecting it", async ({ page }) => {
    await page.evaluate(() => {
        window.dispatchEvent(new Event("auth:expired"));
        window.dispatchEvent(new Event("auth:expired"));
    });
    await expect(page.locator(".passkey-panel")).toBeVisible();
    expect(await authEvents(page)).toEqual([{ type: "logout" }]);
    await expect.poll(async () => (await checkpoint(page)).token).toBe("");
    await recreateWebView(page);
    await expect(page.locator(".passkey-panel")).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBeNull();
    expect((await checkpoint(page)).token).toBe("");
});

test("sign out revokes the server session and clears native login across recreation", async ({ page }) => {
    const logout = page.waitForRequest(request => request.url().endsWith("/api/auth/logout"));
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    const request = await logout;
    expect(request.method()).toBe("POST");
    expect(request.headers().authorization).toBe(["Bearer", seed.token].join(" "));
    await expect(page.locator(".passkey-panel")).toBeVisible();
    expect(await authEvents(page)).toEqual([{ type: "logout" }]);
    await expect.poll(async () => (await checkpoint(page)).token).toBe("");
    expect(await page.evaluate(() => ["authToken", "authLevel", "memberName", "memberId", "memberRole", "usernameRequired"].map(key => sessionStorage.getItem(key)))).toEqual(Array(6).fill(null));
    await recreateWebView(page);
    await expect(page.locator(".passkey-panel")).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBeNull();
});

test("failed sign-out preserves login, while an already-revoked session can sign out", async ({ page }) => {
    await page.route("**/api/auth/logout", interception => interception.fulfill({ status: 503, json: { error: "Unavailable" } }));
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeEnabled();
    expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBe(seed.token);
    expect((await checkpoint(page)).token).toBe(seed.token);
    expect(await authEvents(page)).toEqual([]);
    await page.route("**/api/auth/logout", interception => interception.fulfill({ status: 401, json: { error: "Session ended" } }));
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page.locator(".passkey-panel")).toBeVisible();
    expect(await authEvents(page)).toEqual([{ type: "logout" }]);
});

test.describe("account route", () => {
    test.use({ savedSession: { ...seed, route: "/account", view: {} } });

    test("rehydrates the account identity and preserves /account across recreation", async ({ page }) => {
        await expect(page.getByRole("heading", { name: "Alex", exact: true })).toBeVisible();
        await expect(page.getByRole("heading", { name: "Passkeys", exact: true })).toBeVisible();
        expect((await checkpoint(page)).route).toBe("/account");
        await recreateWebView(page);
        await expect(page.locator(".account-page")).toBeVisible();
        await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
        expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBe(seed.token);
        expect(await page.evaluate(() => localStorage.getItem("defaultUsername"))).toBe("Alex");
    });

    test("requests native browser account management without transferring credentials", async ({ page }) => {
        expect(await page.evaluate(() => window.nativeEvents.filter(event => event.type === "account"))).toEqual([{ type: "account" }]);
        await page.evaluate(() => document.body.appendChild(document.createElement("span")));
        expect(await page.evaluate(() => window.nativeEvents.filter(event => event.type === "account"))).toHaveLength(1);
    });
});

test("SPA account navigation requests browser management", async ({ page }) => {
    await page.evaluate(() => history.pushState({}, "", "/account"));
    expect(await page.evaluate(() => window.nativeEvents.filter(event => event.type === "account"))).toEqual([{ type: "account" }]);
});

test.describe("hosted sign-in routing", () => {
    test.use({ savedSession: signedOut });

    test("routes passkey screens to native browser sign-in but leaves guest invitations in the WebView", async ({ page }) => {
        await page.evaluate(() => {
            const panel = document.querySelector(".passkey-panel");
            panel.setAttribute("aria-labelledby", "invitation-title");
            panel.appendChild(document.createElement("span"));
        });
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
        expect(await page.evaluate(() => window.nativeEvents.filter(event => event.type === "signin"))).toEqual([]);
        await page.evaluate(() => {
            const panel = document.querySelector(".passkey-panel");
            panel.setAttribute("aria-labelledby", "passkey-title");
            panel.appendChild(document.createElement("span"));
        });
        await expect.poll(() => page.evaluate(() => window.nativeEvents.filter(event => event.type === "signin"))).toEqual([{ type: "signin" }]);
        expect((await checkpoint(page)).token).toBe("");
    });

    test("checkpoints leaving an invitation before native sign-in disposes the WebView", async ({ page }) => {
        const snapshots = await page.evaluate(invitation => {
            const panel = document.querySelector(".passkey-panel");
            panel.setAttribute("aria-labelledby", "invitation-title");
            history.replaceState({}, "", `/join/invite#invite=${invitation}`);
            window.KaraokeAndroid.snapshot();
            panel.setAttribute("aria-labelledby", "passkey-title");
            history.pushState({}, "", "/");
            const handoff = window.nativeEvents.findIndex(event => event.type === "signin");
            return window.nativeEvents.slice(0, handoff).filter(event => event.type === "snapshot").map(event => event.state);
        }, invitation);
        expect(snapshots.some(state => state.route === `/join/invite#invite=${invitation}`)).toBe(true);
        expect(snapshots.at(-1).route).toBe("/");
    });
});

test.describe("fixture AuthContext contract", () => {
    test.skip(Boolean(process.env.KARAOKE_FRONTEND_BUILD), "Uses the fixture's AuthContext simulation, not real WebAuthn.");

    test.describe("immediate persistence during view restoration", () => {
        test.use({ savedSession: signedOut, startupSession: passkeySessions.login });

        test("authLevel and user.name each synchronously checkpoint before the UI mounts", async ({ page }) => {
            const snapshots = await page.evaluate(() => window.startupSnapshots.map(event => event.state));
            expect(snapshots).toHaveLength(2);
            expect(snapshots[0]).toMatchObject({ token: "login-session", level: "member", username: "", view: {} });
            expect(snapshots[1]).toMatchObject({ token: "login-session", level: "member", username: "Blair", view: {} });
            expect(await authEvents(page)).toEqual([]);
        });
    });

    test.describe("new passkey and invitation sessions", () => {
        test.use({ savedSession: signedOut });

        for (const transport of ["fetch", "xhr"]) {
            for (const kind of ["register", "login"]) {
                test(`${transport} ${kind} persists token, level, then user.name without waiting for a debounce`, async ({ page }) => {
                    const requests = [];
                    page.on("request", request => {
                        if (request.url().includes(`/api/auth/${kind}/`)) requests.push(request);
                    });
                    const result = await page.evaluate(async ({ kind, transport }) => {
                        window.nativeEvents.length = 0;
                        const success = await window.fixtureAuth.ceremony(kind, { transport, name: kind === "register" ? "Blair" : undefined });
                        return { success, snapshots: window.nativeEvents.filter(event => event.type === "snapshot").map(event => event.state) };
                    }, { kind, transport });
                    expect(result.success).toBe(true);
                    expect(result.snapshots).toHaveLength(2);
                    expect(result.snapshots[0]).toMatchObject({ token: passkeySessions[kind].token, level: passkeySessions[kind].level, username: "" });
                    expect(result.snapshots[1]).toMatchObject({ token: passkeySessions[kind].token, level: passkeySessions[kind].level, username: "Blair" });
                    expect(requests.map(request => new URL(request.url()).pathname)).toEqual([`/api/auth/${kind}/options`, `/api/auth/${kind}/verify`]);
                    expect(requests[0].postDataJSON()).toEqual(kind === "register" ? { name: "Blair" } : {});
                    expect(requests[1].postDataJSON()).toMatchObject({ requestId: "fixture-request", response: { type: "public-key" } });
                    expect(await authEvents(page)).toEqual([]);
                    await recreateWebView(page);
                    await expect(page.locator(".guest-layout")).toBeVisible();
                    expect(await page.evaluate(() => window.KaraokeMobileSession.readSession(sessionStorage, localStorage))).toMatchObject({
                        token: passkeySessions[kind].token, level: passkeySessions[kind].level, username: "Blair"
                    });
                    expect(await page.evaluate(() => sessionStorage.getItem("usernameRequired"))).toBeNull();
                    await expect(page.locator('input[type="password"]')).toHaveCount(0);
                });
            }
        }

        test("guest invitations persist immediately and /auth/me rehydrates them after WebView recreation", async ({ page }) => {
            await page.evaluate(invitation => window.fixtureAuth.joinInvitation(invitation, "Invited guest"), invitation);
            await expect.poll(async () => (await checkpoint(page)).memberId).toBe(memberId);
            expect(await checkpoint(page)).toMatchObject({
                token: guestSession.token, level: "guest", memberId, memberName: "Invited guest", memberRole: "guest", route
            });
            const firstGuestSnapshot = await page.evaluate(() => window.nativeEvents.find(event => event.type === "snapshot" && event.state.level === "guest").state);
            expect(firstGuestSnapshot).toMatchObject({ token: guestSession.token, level: "guest", memberId: "" });
            const rehydration = page.waitForRequest(request => request.url().endsWith("/api/auth/me"));
            await recreateWebView(page);
            expect((await rehydration).headers().authorization).toBe(["Bearer", guestSession.token].join(" "));
            await expect(page.locator(".member-badge")).toContainText("Invited guest");
            expect(await page.evaluate(() => sessionStorage.getItem("authLevel"))).toBe("guest");
            expect((await checkpoint(page)).token).toBe(guestSession.token);
            expect(await authEvents(page)).toEqual([]);
        });
    });

    for (const initial of [seed, signedOut]) {
        test.describe(initial.token ? "existing login" : "no existing login", () => {
            test.use({ savedSession: initial });

            for (const outcome of ["pending", "cancelled"]) {
                test(`${outcome} registration never creates, replaces, or erases saved login`, async ({ page }) => {
                    let verified = 0;
                    await page.route("**/api/auth/register/verify", async interception => {
                        verified++;
                        await interception.fulfill({ json: { pending: true, message: "Waiting for approval" } });
                    });
                    const before = await checkpoint(page);
                    const result = await page.evaluate(cancelled => window.fixtureAuth.ceremony("register", { name: "Blair", cancelled }), outcome === "cancelled");
                    expect(result).toBe(outcome === "pending" ? "pending" : false);
                    expect(verified).toBe(outcome === "pending" ? 1 : 0);
                    expect(await checkpoint(page)).toEqual(before);
                    expect(await authEvents(page)).toEqual([]);
                    expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBe(initial.token || null);
                });
            }
        });
    }

    for (const transport of ["fetch", "xhr"]) {
        test(`${transport} passkey responses alone never capture credentials or save a login`, async ({ page }) => {
            const before = await checkpoint(page);
            for (const kind of ["register", "login"]) {
                const response = await page.evaluate(({ kind, transport }) => window.fixtureAuth.request(`/auth/${kind}/verify`, {
                    transport, method: "POST", body: { requestId: "fixture-request", response: { type: "public-key" } }
                }), { kind, transport });
                expect(response.token).toBe(passkeySessions[kind].token);
            }
            expect(await checkpoint(page)).toEqual(before);
            expect(await authEvents(page)).toEqual([]);
        });

        test(`${transport} rejected passkey options and verification never erase an existing login`, async ({ page }) => {
            let attempts = 0;
            await page.route("**/api/auth/*/options", async interception => {
                attempts++;
                await interception.fulfill({ status: 401, json: { error: "Invalid passkey request" } });
            });
            const before = await checkpoint(page);
            for (const kind of ["login", "register", "passkeys"]) {
                expect(await page.evaluate(({ kind, transport }) => window.fixtureAuth.ceremony(kind, { transport }), { kind, transport })).toBe(false);
            }
            expect(attempts).toBe(3);
            await page.unroute("**/api/auth/*/options");
            await page.route("**/api/auth/*/verify", async interception => {
                attempts++;
                await interception.fulfill({ status: 401, json: { error: "Invalid passkey response" } });
            });
            for (const kind of ["login", "register", "passkeys"]) {
                expect(await page.evaluate(({ kind, transport }) => window.fixtureAuth.ceremony(kind, { transport }), { kind, transport })).toBe(false);
            }
            expect(attempts).toBe(6);
            expect(await checkpoint(page)).toEqual(before);
            expect(await authEvents(page)).toEqual([]);
            expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBe(seed.token);
        });

        for (const failure of [302, 403, 503, "network"]) {
            test(`${transport} /auth/me ${failure} preserves native login despite AuthContext clearing web storage`, async ({ page }) => {
                const before = await checkpoint(page);
                await page.clock.install();
                await page.route("**/api/auth/me", interception => failure === "network"
                    ? interception.abort("failed")
                    : interception.fulfill({ status: failure, json: { error: "Cannot check session" } }));
                await page.evaluate(async transport => {
                    window.nativeEvents.length = 0;
                    await window.fixtureAuth.rehydrate(transport);
                    window.KaraokeAndroid.snapshot();
                    localStorage.setItem("defaultUsername", "Must not overwrite checkpoint");
                }, transport);
                await page.clock.runFor(300);
                await expect(page.locator(".passkey-panel")).toBeVisible();
                expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBeNull();
                expect(await authEvents(page)).toEqual([{ type: "unavailable" }]);
                expect(await page.evaluate(() => window.nativeEvents.filter(event => event.type === "snapshot"))).toEqual([]);
                expect(await checkpoint(page)).toEqual(before);
                await page.unroute("**/api/auth/me");
                await recreateWebView(page);
                await expect(page.locator(".guest-layout")).toBeVisible();
                expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBe(seed.token);
                expect(await page.evaluate(() => localStorage.getItem("defaultUsername"))).toBe("Alex");
            });
        }

        test(`${transport} /auth/me 401 clears the native checkpoint rather than reporting an outage`, async ({ page }) => {
            await page.route("**/api/auth/me", interception => interception.fulfill({ status: 401, json: { error: "Expired" } }));
            await page.evaluate(transport => window.fixtureAuth.rehydrate(transport), transport);
            await expect(page.locator(".passkey-panel")).toBeVisible();
            expect(await authEvents(page)).toEqual([{ type: "logout" }]);
            expect((await checkpoint(page)).token).toBe("");
            await recreateWebView(page);
            await expect(page.locator(".passkey-panel")).toBeVisible();
            expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBeNull();
        });

        for (const failure of [503, "network"]) {
            test(`${transport} stale /auth/me ${failure} cannot clear or freeze a newer session`, async ({ page }) => {
                let release;
                const requested = new Promise(resolve => { release = resolve; });
                await page.route("**/api/auth/me", interception => release(interception));
                await page.evaluate(transport => { window.pendingRehydration = window.fixtureAuth.rehydrate(transport); }, transport);
                const interception = await requested;
                await page.evaluate(value => window.fixtureAuth.persistSession(value), passkeySessions.login);
                if (failure === "network") await interception.abort("failed");
                else await interception.fulfill({ status: failure, json: { error: "Old request failed" } });
                await page.evaluate(() => window.pendingRehydration);
                await page.evaluate(() => {
                    localStorage.setItem("defaultUsername", "Current account");
                    window.KaraokeAndroid.snapshot();
                });
                expect(await authEvents(page)).toEqual([]);
                expect(await checkpoint(page)).toMatchObject({ token: "login-session", level: "member", username: "Current account" });
                expect(await page.evaluate(() => sessionStorage.getItem("authToken"))).toBe("login-session");
            });
        }
    }

    test("only canonical invitation fragments and safe account routes reach native snapshots", async ({ page }) => {
        for (const [address, expected] of [
            [`/join/invite#invite=${invitation}`, `/join/invite#invite=${invitation}`],
            [`/join/invite#invite=${invitation.slice(1)}`, "/join/invite"],
            [`/join/invite?token=discard#invite=${invitation}`, "/join/invite"],
            [`/join/invite#invite=${invitation}&extra=discard`, "/join/invite"],
            [`/guest/${partyId}/${memberId}#invite=${invitation}`, route],
            ["/account?token=discard#discard", "/account"],
            ["/unknown#discard", "/"]
        ]) {
            await page.evaluate(address => {
                history.replaceState({}, "", address);
                window.KaraokeAndroid.snapshot();
            }, address);
            expect((await checkpoint(page)).route).toBe(expected);
        }
    });
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