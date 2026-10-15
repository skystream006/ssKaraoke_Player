const { test, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");

const asset = name => fs.readFileSync(path.resolve(__dirname, "../../app/src/main/assets", name), "utf8");
const partyId = "11111111-1111-4111-8111-111111111111";
const memberId = "22222222-2222-4222-8222-222222222222";
const songs = [
    { id: "song-1", video_title: "Song 10", position: 1, rating: 2, status: "playing" },
    { id: "song-2", video_title: "alpha", position: 2, rating: "5", status: "queued" },
    { id: "song-3", video_title: "Song 2", position: 3, rating: 5, status: "played" },
    { id: "song-4", video_title: "ALPHA", position: 4, rating: null, status: "paused" },
    { id: "song-5", video_title: "Zulu", position: 5, status: "queued" }
];
const nameOrder = ["song-2", "song-4", "song-3", "song-1", "song-5"];
const ratingOrder = ["song-2", "song-3", "song-1", "song-4", "song-5"];
const ids = queue => queue.map(item => item.id);
const positions = order => order.map((id, index) => ({ id, position: index + 1 }));

for (const role of ["guest", "organizer"]) {
    test.describe(`${role} sorting`, () => {
        let state;
        const route = `/${role}/${partyId}/${memberId}`;
        test.beforeEach(async ({ page, context }) => {
            test.skip(!!process.env.KARAOKE_FRONTEND_BUILD, "Sorting fixture simulates server queue broadcasts.");
            state = { queue: structuredClone(songs), locked: false, writes: [], reads: [] };
            const seed = {
                token: "test-session", level: "member", username: "Alex", memberId, memberName: "Alex",
                memberRole: role, route, theme: "ocean", view: { tab: role === "guest" ? "queue" : "playlist" }
            };
            await context.addInitScript({ content: `
                window.KaraokeHost = {postMessage() {}};
                window.__karaokeSeed = ${JSON.stringify(seed)};
                window.__karaokeThemes = ${asset("color-themes.json")};
                window.__karaokeCss = ${JSON.stringify(asset("mobile-shell.css"))};
                ${asset("mobile-session.js")}
                ${asset("mobile-shell.js")}
            ` });
            await page.route("**/api/**", async interception => {
                const request = interception.request();
                const pathname = new URL(request.url()).pathname;
                if (request.method() === "GET") state.reads.push(pathname);
                if (pathname === `/api/queue/${partyId}/reorder`) {
                    state.writes.push(request.postDataJSON());
                    expect(request.method()).toBe("PUT");
                    expect(request.headers().authorization).toBe(["Bearer", "test-session"].join(" "));
                    const order = request.postDataJSON().order;
                    state.queue = order.map(({ id, position }) => ({ ...state.queue.find(item => item.id === id), position }));
                    await interception.fulfill({ json: { success: true } });
                    return;
                }
                const body = pathname === "/api/auth/me" ? { level: "member", user: { name: "Alex" } }
                    : pathname === `/api/queue/${partyId}` ? state.queue
                    : { id: partyId, is_locked: state.locked };
                await interception.fulfill({ json: body });
            });
            await page.goto(route);
            await expect(page.locator(".playlist-item")).toHaveCount(songs.length);
            await expect(page.getByRole("group", { name: role === "guest" ? "Queue sorting" : "Playlist sorting" })).toBeVisible();
        });

        for (const [mode, order] of [["name", nameOrder], ["rating", ratingOrder]]) {
            test(`sorts by ${mode} and saves the shared order without restarting playback`, async ({ page }) => {
                const status = page.locator(".sskaraoke-sort-status");
                await page.evaluate(() => { window.sameDocument = true; });
                await page.getByRole("button", { name: `Sort by ${mode}`, exact: true }).click();
                await expect(status).toHaveText(`Sorted by ${mode}. Shared song order saved.`);
                expect(state.writes).toEqual([{ order: positions(order) }]);
                expect(state.reads).toContain(`/api/parties/${partyId}`);
                expect(ids(state.queue)).toEqual(order);
                for (const item of state.queue) expect(item.status).toBe(songs.find(song => song.id === item.id).status);
                // The hosted React client refreshes from the server's queue:update broadcast.
                await page.evaluate(() => window.fixtureQueue.refresh());
                await expect(page.locator(".playlist-title")).toHaveText(order.map(id => songs.find(song => song.id === id).video_title));
                expect(await page.evaluate(() => window.sameDocument)).toBe(true);
                await page.reload();
                await expect(page.locator(".playlist-title")).toHaveText(state.queue.map(item => item.video_title));
                await page.getByRole("button", { name: `Sort by ${mode}`, exact: true }).click();
                await expect(status).toHaveText(`Already sorted by ${mode}.`);
                expect(state.writes).toHaveLength(1);
            });
        }

        test("sorts the latest server queue, keeping rating ties and unrated songs in position order", async ({ page }) => {
            state.queue = [
                { ...songs[0], position: 20, rating: false },
                { ...songs[1], position: 7, rating: "5" },
                { ...songs[2], position: 4, rating: 5 },
                { ...songs[3], position: 30, rating: "not-a-rating" },
                { ...songs[4], position: 15, rating: 0 },
                { id: "new-song", video_title: "New arrival", position: 35, rating: 4 }
            ];
            await page.getByRole("button", { name: "Sort by rating", exact: true }).click();
            await expect(page.locator(".sskaraoke-sort-status")).toContainText("Shared song order saved.");
            expect(state.writes).toEqual([{ order: positions(["song-3", "song-2", "new-song", "song-5", "song-1", "song-4"]) }]);
        });

        test("does not pretend to sort by ratings when the server supplies none", async ({ page }) => {
            state.queue.forEach((item, index) => { item.rating = [undefined, null, "", "NaN", 6][index]; });
            await page.getByRole("button", { name: "Sort by rating", exact: true }).click();
            await expect(page.locator(".sskaraoke-sort-status")).toContainText("No song ratings available.");
            expect(state.writes).toEqual([]);
            await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeEnabled();
        });

        test("refuses locked queues and disabled drag permissions", async ({ page }) => {
            state.locked = true;
            await page.getByRole("button", { name: "Sort by name", exact: true }).click();
            await expect(page.locator(".sskaraoke-sort-status")).toContainText("Party is locked.");
            expect(state.writes).toEqual([]);
            state.locked = false;
            await page.evaluate(() => document.querySelectorAll(".drag-handle").forEach(handle => {
                handle.removeAttribute("data-rfd-drag-handle-draggable-id");
            }));
            await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeDisabled();
            await expect(page.getByRole("button", { name: "Sort by rating", exact: true })).toBeDisabled();
        });

        for (const failure of [401, 403, 423, 500, "network"]) {
            test(`handles a ${failure} reorder failure without changing the list or retrying`, async ({ page }) => {
                let attempts = 0;
                await page.route(`**/api/queue/${partyId}/reorder`, async interception => {
                    attempts++;
                    if (failure === "network") await interception.abort();
                    else await interception.fulfill({ status: failure, json: { error: "<script>Untrusted server message</script>" } });
                });
                await page.getByRole("button", { name: "Sort by name", exact: true }).click();
                const expected = failure === 401 ? "Your session has expired."
                    : failure === 403 ? "You do not have permission"
                    : failure === 423 ? "Party is locked." : "Unable to confirm sorting.";
                await expect(page.locator(".sskaraoke-sort-status")).toContainText(expected);
                await expect(page.locator(".playlist-title")).toHaveText(songs.map(item => item.video_title));
                await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeEnabled();
                expect(attempts).toBe(1);
                expect(state.writes).toEqual([]);
            });
        }

        test("prevents duplicate submissions and cancels a stale-session sort before writing", async ({ page }) => {
            let release;
            const requested = new Promise(resolve => { release = resolve; });
            await page.route(`**/api/queue/${partyId}`, interception => release(interception));
            await page.getByRole("button", { name: "Sort by name", exact: true }).click();
            const interception = await requested;
            await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeDisabled();
            await expect(page.getByRole("button", { name: "Sort by rating", exact: true })).toBeDisabled();
            await page.evaluate(() => {
                document.querySelectorAll(".sskaraoke-sort button").forEach(button => button.click());
                sessionStorage.setItem("authToken", "another-session");
            });
            await interception.fulfill({ json: state.queue });
            await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeEnabled();
            await expect(page.locator(".sskaraoke-sort-status")).toContainText("Sorting interrupted.");
            expect(state.writes).toEqual([]);
        });

        test("does not sort the old party after navigation during a read", async ({ page }) => {
            let release;
            const requested = new Promise(resolve => { release = resolve; });
            await page.route(`**/api/queue/${partyId}`, interception => release(interception));
            await page.getByRole("button", { name: "Sort by name", exact: true }).click();
            const interception = await requested;
            await page.evaluate(() => history.pushState({}, "", "/account"));
            await interception.fulfill({ json: state.queue });
            await expect(page.locator(".sskaraoke-sort")).toHaveCount(0);
            expect(state.writes).toEqual([]);
        });

        test("times out a stalled read without writing or leaving controls busy", async ({ page }) => {
            await page.clock.install();
            let release;
            const requested = new Promise(resolve => { release = resolve; });
            await page.route(`**/api/queue/${partyId}`, interception => release(interception));
            await page.getByRole("button", { name: "Sort by name", exact: true }).click();
            await requested;
            await page.clock.runFor(16000);
            await expect(page.locator(".sskaraoke-sort-status")).toContainText("Unable to confirm sorting.");
            await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeEnabled();
            expect(state.writes).toEqual([]);
        });

        test("rejects API redirects without following them with credentials", async ({ page }) => {
            let forwarded = false;
            await page.route("**/redirect-target", async interception => {
                forwarded = true;
                await interception.fulfill({ json: state.queue });
            });
            await page.route(`**/api/queue/${partyId}`, interception =>
                interception.fulfill({ status: 302, headers: { Location: "/redirect-target" } }));
            await page.getByRole("button", { name: "Sort by name", exact: true }).click();
            await expect(page.locator(".sskaraoke-sort-status")).toContainText("Unable to confirm sorting.");
            expect(forwarded).toBe(false);
            expect(state.writes).toEqual([]);
        });

        test("keeps controls unique after list replacement and disables empty/single-item/dragging lists", async ({ page }) => {
            await page.evaluate(() => {
                const list = document.querySelector(".playlist-list");
                list.replaceWith(list.cloneNode(true));
            });
            await expect(page.locator(".sskaraoke-sort")).toHaveCount(1);
            await page.evaluate(() => document.querySelector(".sskaraoke-sort").remove());
            await expect(page.locator(".sskaraoke-sort")).toHaveCount(1);
            await page.evaluate(() => document.querySelector(".playlist-item").classList.add("dragging"));
            await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeDisabled();
            state.queue = songs.slice(0, 1);
            await page.evaluate(() => window.fixtureQueue.refresh());
            await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeDisabled();
            state.queue = [];
            await page.evaluate(() => window.fixtureQueue.refresh());
            await expect(page.getByRole("button", { name: "Sort by rating", exact: true })).toBeDisabled();
            expect(state.writes).toEqual([]);
        });

        test("rejects malformed queue responses and stays usable", async ({ page }) => {
            for (const invalid of [
                [{ ...songs[0], id: "duplicate" }, { ...songs[1], id: "duplicate" }],
                [{ ...songs[0], video_title: null }],
                [{ ...songs[0], position: null }],
                [{ ...songs[0], position: -1 }],
                { error: "not a queue" }
            ]) {
                state.queue = invalid;
                await page.getByRole("button", { name: "Sort by name", exact: true }).click();
                await expect(page.locator(".sskaraoke-sort-status")).toContainText("Unable to confirm sorting.");
                await expect(page.getByRole("button", { name: "Sort by name", exact: true })).toBeEnabled();
            }
            expect(state.writes).toEqual([]);
        });
    });
}
