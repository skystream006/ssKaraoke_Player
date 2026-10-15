const { test, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");

const asset = name => fs.readFileSync(path.resolve(__dirname, "../../app/src/main/assets", name), "utf8");
const partyId = "11111111-1111-4111-8111-111111111111";
const endpoint = `/api/queue/${partyId}`;
const initialQueue = ["playing", "paused", "played", "queued"].map((status, index) => ({
    id: `song-${index}`, position: index + 1, status,
    video_id: "same-video", video_title: index < 2 ? "Same song" : `Song ${index}`, singer_name: "Alex"
}));
const ids = page => page.locator(".playlist-item").evaluateAll(items => items.map(item => item.dataset.rfdDraggableId));
const row = (page, id) => page.locator(`.playlist-item[data-rfd-draggable-id="${id}"]`);
const move = async (page, id, direction) => {
    await row(page, id).getByRole("button", { name: `Move ${direction}` }).click();
    await expect(page.locator(".playlist-item.dragging")).toHaveCount(0);
};

for (const view of ["playlist", "queue"]) {
    test.describe(view, () => {
        let queue;
        let writes;
        let errors;
        test.beforeEach(async ({ context, page }) => {
            queue = initialQueue.map(item => ({ ...item }));
            writes = [];
            errors = [];
            page.on("pageerror", error => errors.push(error.message));
            await context.addInitScript({ content: `
                window.nativeEvents = [];
                window.KaraokeHost = { postMessage: message => window.nativeEvents.push(JSON.parse(message)) };
                window.__karaokeSeed = { token: "test-session", level: "member", theme: "ocean", view: {} };
                window.__karaokeThemes = ${asset("color-themes.json")};
                window.__karaokeCss = ${JSON.stringify(asset("mobile-shell.css"))};
                ${asset("mobile-session.js")}
                ${asset("mobile-shell.js")}
            ` });
            await page.route("**/api/queue/**", async route => {
                const request = route.request();
                if (request.method() === "PUT") {
                    writes.push(request.postDataJSON());
                    queue = request.postDataJSON().order.map(({ id, position }) => ({ ...queue.find(item => item.id === id), position }));
                    await route.fulfill({ json: { success: true } });
                } else {
                    await route.fulfill({ json: queue });
                }
            });
            await page.goto(`/reorder-fixture.html?view=${view}`);
            await expect(page.locator(".sskaraoke-reorder-controls")).toHaveCount(queue.length);
        });
        test.afterEach(() => expect(errors).toEqual([]));

        test("moves every playback status using queue-entry IDs and persists the shared order", async ({ page }) => {
            const request = page.waitForRequest(request => request.method() === "PUT");
            await move(page, "song-0", "down");
            expect((await request).headers().authorization).toBe(["Bearer", "test-session"].join(" "));
            await expect.poll(() => ids(page)).toEqual(["song-1", "song-0", "song-2", "song-3"]);
            await move(page, "song-1", "down");
            await move(page, "song-2", "up");
            await move(page, "song-3", "up");
            await expect.poll(() => ids(page)).toEqual(["song-0", "song-2", "song-3", "song-1"]);
            expect(writes).toHaveLength(4);
            expect(writes[3]).toEqual({ order: [
                { id: "song-0", position: 1 }, { id: "song-2", position: 2 },
                { id: "song-3", position: 3 }, { id: "song-1", position: 4 }
            ] });
            expect(queue.map(item => item.status)).toEqual(["playing", "played", "queued", "paused"]);
            await page.reload();
            await expect.poll(() => ids(page)).toEqual(["song-0", "song-2", "song-3", "song-1"]);
        });

        test("disables boundaries and handles single/empty lists without sending requests", async ({ page }) => {
            await expect(row(page, "song-0").getByRole("button", { name: "Move up" })).toBeDisabled();
            await expect(row(page, "song-3").getByRole("button", { name: "Move down" })).toBeDisabled();
            await page.evaluate(items => window.sortFixture.setQueue(items), initialQueue.slice(0, 1));
            await expect(page.locator(".sskaraoke-reorder-controls button:disabled")).toHaveCount(2);
            await page.getByRole("button", { name: "Remove", exact: true }).click();
            await expect(page.locator(".sskaraoke-reorder-controls")).toHaveCount(0);
            expect(writes).toEqual([]);
        });

        test("respects disabled reordering and updates controls after permission changes", async ({ page }) => {
            await page.evaluate(() => window.sortFixture.setAllowed(false));
            await expect(page.locator(".sskaraoke-reorder-controls")).toHaveCount(0);
            await page.evaluate(() => window.sortFixture.setAllowed(true));
            await expect(page.locator(".sskaraoke-reorder-controls")).toHaveCount(4);
            await page.evaluate(() => document.querySelector(".drag-handle").setAttribute("aria-disabled", "true"));
            await expect(row(page, "song-0").locator(".sskaraoke-reorder-controls button:disabled")).toHaveCount(2);
            expect(writes).toEqual([]);
        });

        for (const status of [403, 423, 500]) {
            test(`leaves ${status} errors and rollback to the hosted client without replay`, async ({ page }) => {
                let attempts = 0;
                await page.route(`**${endpoint}/reorder`, async route => {
                    attempts++;
                    await route.fulfill({ status, json: { error: "Reordering denied" } });
                });
                await move(page, "song-2", "up");
                await expect(page.getByRole("alert")).toHaveText("Failed to reorder queue.");
                await expect.poll(() => ids(page)).toEqual(initialQueue.map(item => item.id));
                expect(attempts).toBe(1);
            });
        }

        test("keeps controls unique through rerenders, live changes, and tab switches", async ({ page }) => {
            await page.evaluate(items => window.sortFixture.setQueue(items), [...initialQueue].reverse().map((item, index) => ({ ...item, position: index + 1 })));
            await expect.poll(() => ids(page)).toEqual(["song-3", "song-2", "song-1", "song-0"]);
            await page.getByRole("button", { name: "Search", exact: true }).click();
            await page.getByRole("button", { name: view === "playlist" ? "Playlist" : "Queue", exact: true }).click();
            await expect(page.locator(".sskaraoke-reorder-controls")).toHaveCount(4);
            await move(page, "song-3", "down");
            await expect.poll(() => ids(page)).toEqual(["song-2", "song-3", "song-1", "song-0"]);
            expect(writes).toHaveLength(1);
        });

        test("supports keyboard activation and keeps touch controls within the viewport", async ({ page }) => {
            const button = row(page, "song-0").getByRole("button", { name: "Move down" });
            await button.focus();
            await page.keyboard.press("Enter");
            await expect.poll(() => ids(page)).toEqual(["song-1", "song-0", "song-2", "song-3"]);
            for (const control of await page.locator(".sskaraoke-reorder-controls button").all()) {
                const box = await control.boundingBox();
                expect(box.width).toBeGreaterThanOrEqual(44);
                expect(box.height).toBeGreaterThanOrEqual(48);
                expect(box.x).toBeGreaterThanOrEqual(0);
                expect(box.x + box.width).toBeLessThanOrEqual(page.viewportSize().width);
            }
        });

        test("retains touch drag, haptics, and tab-swipe exclusions", async ({ page, context }) => {
            const handles = page.locator(".drag-handle");
            await handles.first().scrollIntoViewIfNeeded();
            const first = await handles.first().boundingBox();
            const second = await handles.nth(1).boundingBox();
            const session = await context.newCDPSession(page);
            const x = first.x + first.width / 2;
            const y = first.y + first.height / 2;
            await session.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x, y, id: 1 }] });
            await expect(page.locator(".playlist-item.dragging")).toHaveCount(1);
            await expect(page.locator(".sskaraoke-reorder-controls button:enabled")).toHaveCount(0);
            await session.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x, y: second.y + second.height / 2, id: 1 }] });
            await session.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
            await expect.poll(() => ids(page)).toEqual(["song-1", "song-0", "song-2", "song-3"]);
            expect(writes).toHaveLength(1);
            expect(await page.evaluate(() => window.nativeEvents.some(event => event.type === "haptic"))).toBe(true);

            await page.locator(".sskaraoke-reorder-controls button").first().evaluate(button => {
                const start = new Touch({ identifier: 1, target: button, clientX: 300, clientY: 100 });
                button.dispatchEvent(new TouchEvent("touchstart", { bubbles: true, touches: [start], changedTouches: [start] }));
            });
            await page.waitForTimeout(100);
            await page.locator(".sskaraoke-reorder-controls button").first().evaluate(button => {
                const end = new Touch({ identifier: 1, target: button, clientX: 100, clientY: 100 });
                button.dispatchEvent(new TouchEvent("touchend", { bubbles: true, touches: [], changedTouches: [end] }));
            });
            await expect(page.locator(".mobile-tab.active, .sidebar-tab.active")).toHaveText(view === "playlist" ? "Playlist" : "Queue");
            expect(writes).toHaveLength(1);
        });
    });
}
