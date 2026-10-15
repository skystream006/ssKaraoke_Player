(function () {
    "use strict";
    if (window !== window.top || window.KaraokeAndroid || !window.KaraokeHost) return;
    const contract = window.KaraokeMobileSession;
    const seed = window.__karaokeSeed || {};
    delete window.__karaokeSeed;
    contract.restoreSession(sessionStorage, localStorage, seed);
    const themes = window.__karaokeThemes;
    delete window.__karaokeThemes;
    const scrollSelectors = [".sidebar-body", ".guest-main", ".queue-section", ".search-section"];
    let snapshotTimer;
    let authenticationUnavailable = false;
    let restoring = true;
    let lastSnapshot = "";
    let browserAuthScreen = "";
    const sortControls = new Map();
    let sorting = false;

    function canSort(list) {
        return list.querySelectorAll(".playlist-item").length > 1 &&
            !!list.querySelector(".drag-handle[data-rfd-drag-handle-draggable-id]:not([aria-disabled=true])") &&
            !document.querySelector(".playlist-item.dragging");
    }

    function songRating(item) {
        const value = item.rating;
        if (typeof value !== "number" && typeof value !== "string" || String(value).trim() === "") return 0;
        const rating = Number(value);
        return Number.isFinite(rating) && rating > 0 && rating <= 5 ? rating : 0;
    }

    async function sortSongs(list, mode, status) {
        const route = location.pathname;
        const partyId = route.match(/^\/(?:organizer|guest)\/([a-z0-9-]+)\/[a-z0-9-]+\/?$/i)?.[1];
        const token = sessionStorage.getItem("authToken");
        if (sorting || !partyId || !token || !canSort(list)) return;
        const current = () => list.isConnected && location.pathname === route &&
            sessionStorage.getItem("authToken") === token;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 15000);
        const request = async (path, body) => {
            const response = await fetch(path, {
                method: body ? "PUT" : "GET",
                headers: { Authorization: ["Bearer", token].join(" "), "Content-Type": "application/json" },
                body: body ? JSON.stringify(body) : undefined,
                cache: "no-store", redirect: "error", signal: controller.signal
            });
            if (!response.ok) throw Object.assign(new Error(), { status: response.status });
            return response;
        };
        sorting = true;
        status.textContent = "Sorting…";
        syncSortControls();
        try {
            const party = await (await request(`/api/parties/${partyId}`)).json();
            if (party.is_locked) throw Object.assign(new Error(), { status: 423 });
            if (!current()) return;
            const queue = await (await request(`/api/queue/${partyId}`)).json();
            if (!Array.isArray(queue) || queue.some(item => !item || typeof item.id !== "string" || !item.id ||
                typeof item.video_title !== "string" || !Number.isSafeInteger(item.position) || item.position < 1) ||
                new Set(queue.map(item => item.id)).size !== queue.length) throw new Error();
            if (!current() || !canSort(list)) return;
            if (mode === "rating" && !queue.some(item => songRating(item) > 0)) {
                status.textContent = "No song ratings available. The server must provide ratings to sort by rating.";
                return;
            }
            const ordered = [...queue].sort((a, b) => a.position - b.position);
            const compareNames = new Intl.Collator(undefined, { sensitivity: "base", numeric: true }).compare;
            const sorted = [...ordered].sort((a, b) => mode === "rating"
                ? songRating(b) - songRating(a)
                : compareNames(a.video_title.trim(), b.video_title.trim()));
            if (sorted.every((item, index) => item.id === ordered[index].id)) {
                status.textContent = `Already sorted by ${mode}.`;
                return;
            }
            await request(`/api/queue/${partyId}/reorder`, {
                order: sorted.map((item, index) => ({ id: item.id, position: index + 1 }))
            });
            if (current()) status.textContent = `Sorted by ${mode}. Shared song order saved.`;
        } catch (error) {
            if (current()) status.textContent = error.status === 423 ? "Party is locked. Song order cannot be changed."
                : error.status === 401 ? "Your session has expired. Sign in again to sort songs."
                : error.status === 403 ? "You do not have permission to sort this list."
                : "Unable to confirm sorting. Check your connection and the current order before trying again.";
        } finally {
            clearTimeout(timeout);
            sorting = false;
            if (status.textContent === "Sorting…") status.textContent = "Sorting interrupted. Check the current order.";
            syncSortControls();
        }
    }

    function syncSortControls() {
        const partyRoute = /^\/(?:organizer|guest)\/[a-z0-9-]+\/[a-z0-9-]+\/?$/i.test(location.pathname);
        for (const [list, controls] of sortControls) {
            if (!list.isConnected || !controls.isConnected || !partyRoute || !sessionStorage.getItem("authToken")) {
                controls.remove();
                sortControls.delete(list);
            }
        }
        if (!partyRoute || !sessionStorage.getItem("authToken")) return;
        for (const list of document.querySelectorAll(".playlist-list")) {
            let controls = sortControls.get(list);
            if (!controls) {
                controls = document.createElement("div");
                controls.className = "sskaraoke-sort";
                controls.setAttribute("role", "group");
                controls.setAttribute("aria-label", location.pathname.startsWith("/organizer/") ? "Playlist sorting" : "Queue sorting");
                const status = document.createElement("div");
                status.className = "sskaraoke-sort-status";
                status.setAttribute("role", "status");
                status.textContent = "Changes the shared song order.";
                for (const [mode, direction] of [["name", "A–Z"], ["rating", "highest first"]]) {
                    const button = document.createElement("button");
                    button.type = "button";
                    button.textContent = `Sort by ${mode}`;
                    button.title = `Sort by ${mode} (${direction})`;
                    button.addEventListener("click", () => sortSongs(list, mode, status));
                    controls.appendChild(button);
                }
                controls.appendChild(status);
                list.before(controls);
                sortControls.set(list, controls);
            }
            controls.setAttribute("aria-busy", String(sorting));
            for (const button of controls.querySelectorAll("button")) button.disabled = sorting || !canSort(list);
        }
    }

    function send(type, payload = {}) {
        window.KaraokeHost.postMessage(JSON.stringify({ type, ...payload }));
    }

    function tabKey(button) {
        return button?.textContent.toLowerCase().match(/playlist|search|queue|settings|status/)?.[0] || "";
    }

    function tabs() {
        return Array.from(document.querySelectorAll(".mobile-tabs .mobile-tab, .sidebar-tabs .sidebar-tab"));
    }

    function snapshot() {
        if (authenticationUnavailable) return;
        const scroll = { window: Math.round(window.scrollY) };
        for (const selector of scrollSelectors) {
            const element = document.querySelector(selector);
            if (element) scroll[selector] = Math.round(element.scrollTop);
        }
        const state = {
            ...contract.readSession(sessionStorage, localStorage),
            route: contract.safeLocation(location.href, location.origin),
            theme: localStorage.getItem("karaokeTheme") || "neonPurple",
            view: restoring ? seed.view || {} : { tab: tabKey(tabs().find(button => button.classList.contains("active"))), scroll }
        };
        const serialized = JSON.stringify(state);
        if (serialized !== lastSnapshot) {
            lastSnapshot = serialized;
            send("snapshot", { state });
        }
    }

    function scheduleSnapshot() {
        clearTimeout(snapshotTimer);
        snapshotTimer = setTimeout(snapshot, 250);
    }

    function routeBrowserAuthentication() {
        if (authenticationUnavailable) return;
        const screen = location.pathname.replace(/\/$/, "") === "/account" ? "account"
            : !sessionStorage.getItem("authToken") &&
                document.querySelector(".passkey-panel[aria-labelledby='passkey-title']") ? "signin" : "";
        if (screen && screen !== browserAuthScreen) {
            snapshot();
            send(screen);
        }
        browserAuthScreen = screen;
    }

    function applyTheme(key) {
        if (!Object.prototype.hasOwnProperty.call(themes, key)) return;
        localStorage.setItem("karaokeTheme", key);
        Object.entries(themes[key]).forEach(([property, value]) => document.documentElement?.style.setProperty(property, value));
        if (document.body) document.body.style.backgroundColor = themes[key]["--bg-dark"];
        scheduleSnapshot();
    }

    applyTheme(seed.theme || localStorage.getItem("karaokeTheme") || "neonPurple");

    function apiRequest(url) {
        try {
            const address = new URL(url, location.origin);
            return address.origin === location.origin && address.pathname.startsWith("/api/") ? address.pathname : "";
        } catch (_) { return ""; }
    }

    function sessionResponse(path, token, status) {
        // AuthContext clears storage on any /me failure, including temporary outages.
        if (path !== "/api/auth/me" || !token || token !== sessionStorage.getItem("authToken")) return;
        if (status !== 401 && (status < 200 || status >= 300) && !authenticationUnavailable) {
            authenticationUnavailable = true;
            send("unavailable");
        }
    }

    const originalFetch = window.fetch;
    if (originalFetch) window.fetch = async function (input, options) {
        const path = apiRequest(typeof input === "string" || input instanceof URL ? input : input.url);
        const token = sessionStorage.getItem("authToken");
        try {
            const response = await originalFetch.apply(this, arguments);
            sessionResponse(path, token, response.status);
            return response;
        } catch (error) {
            sessionResponse(path, token, 0);
            throw error;
        }
    };

    const requests = new WeakMap();
    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
        const result = originalOpen.apply(this, arguments);
        const request = { path: apiRequest(url), token: null };
        requests.set(this, request);
        if (request.path === "/api/auth/me") {
            // Install before Axios assigns onerror, whose rejection clears sessionStorage.
            const events = ["load", "error", "timeout", "abort"];
            const finished = () => {
                for (const event of events) this.removeEventListener(event, finished, true);
                sessionResponse(request.path, request.token, this.status);
            };
            for (const event of events) this.addEventListener(event, finished, { capture: true });
        }
        return result;
    };
    XMLHttpRequest.prototype.send = function (body) {
        const request = requests.get(this);
        if (request) request.token = sessionStorage.getItem("authToken");
        return originalSend.apply(this, arguments);
    };

    for (const method of ["setItem", "removeItem", "clear"]) {
        const original = Storage.prototype[method];
        Storage.prototype[method] = function (key) {
            const signingOut = this === sessionStorage && sessionStorage.getItem("authToken") &&
                (method === "clear" || method === "removeItem" && key === "authToken");
            const result = original.apply(this, arguments);
            if (signingOut && !authenticationUnavailable) send("logout");
            if (this === sessionStorage && key === "authLevel" || this === localStorage && key === "defaultUsername") snapshot();
            if (this === sessionStorage || this === localStorage) scheduleSnapshot();
            return result;
        };
    }

    for (const method of ["pushState", "replaceState"]) {
        const original = history[method];
        history[method] = function () {
            const previousPath = location.pathname;
            const result = original.apply(this, arguments);
            if (location.pathname !== previousPath) restoring = false;
            routeBrowserAuthentication();
            scheduleSnapshot();
            return result;
        };
    }
    window.addEventListener("popstate", () => { routeBrowserAuthentication(); scheduleSnapshot(); });
    window.addEventListener("pagehide", snapshot);
    document.addEventListener("visibilitychange", snapshot);
    document.addEventListener("scroll", scheduleSnapshot, { passive: true, capture: true });
    document.addEventListener("click", scheduleSnapshot, true);

    let touchStart = null;
    const excluded = "input, textarea, select, button, a, iframe, video, [contenteditable=true], [role=slider], .drag-handle, [data-rfd-drag-handle-draggable-id], .main-video-section";
    document.addEventListener("touchstart", event => {
        touchStart = null;
        if (event.touches.length !== 1 || !(event.target instanceof Element) || event.target.closest(excluded)) return;
        if (!event.target.closest(".guest-main, .sidebar-body")) return;
        touchStart = { x: event.touches[0].clientX, y: event.touches[0].clientY, time: performance.now() };
    }, { passive: true });
    document.addEventListener("touchcancel", () => { touchStart = null; }, { passive: true });
    document.addEventListener("touchend", event => {
        const start = touchStart;
        touchStart = null;
        if (!start || event.touches.length || !event.changedTouches.length || document.querySelector(".playlist-item.dragging")) return;
        const direction = contract.swipeDirection(start, { x: event.changedTouches[0].clientX, y: event.changedTouches[0].clientY, time: performance.now() });
        if (!direction) return;
        const available = tabs();
        const selected = available.findIndex(button => button.classList.contains("active"));
        const next = available[selected + direction];
        if (selected >= 0 && next && !next.disabled) {
            next.click();
            send("haptic");
        }
    }, { passive: true });

    function attach() {
        applyTheme(localStorage.getItem("karaokeTheme") || "neonPurple");
        document.documentElement.dataset.sskaraokeAndroid = "true";
        const style = document.createElement("style");
        style.textContent = window.__karaokeCss || "";
        delete window.__karaokeCss;
        document.head.appendChild(style);
        const started = performance.now();
        let wasDragging = false;
        let restoreQueued = false;
        const restoreView = () => {
            routeBrowserAuthentication();
            syncSortControls();
            const dragging = !!document.querySelector(".playlist-item.dragging");
            if (dragging && !wasDragging) send("haptic");
            wasDragging = dragging;
            if (!restoring || restoreQueued) return;
            const available = tabs();
            if (!available.length && performance.now() - started < 15000 && !document.querySelector(".passkey-panel, .error-screen")) return;
            restoreQueued = true;
            requestAnimationFrame(() => {
                if (seed.route === location.pathname && seed.view) {
                    const selected = available.find(button => tabKey(button) === seed.view.tab);
                    if (selected && !selected.classList.contains("active")) selected.click();
                }
                requestAnimationFrame(() => {
                    if (seed.route === location.pathname && seed.view?.scroll) {
                        window.scrollTo(0, seed.view.scroll.window || 0);
                        for (const selector of scrollSelectors) {
                            const element = document.querySelector(selector);
                            if (element) element.scrollTop = seed.view.scroll[selector] || 0;
                        }
                    }
                    restoring = false;
                    snapshot();
                });
            });
        };
        const observer = new MutationObserver(restoreView);
        observer.observe(document.body, { childList: true, subtree: true, attributes: true,
            attributeFilter: ["class", "aria-disabled", "data-rfd-drag-handle-draggable-id"] });
        restoreView();
        setTimeout(() => { restoring = false; snapshot(); }, 15000);
        send("ready");
    }

    window.KaraokeAndroid = { snapshot, applyTheme };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", attach, { once: true });
    else attach();
})();