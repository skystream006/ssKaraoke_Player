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
    let renewalRequested = false;
    let restoring = true;
    let lastSnapshot = "";

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
        if (restoring) return;
        const scroll = { window: Math.round(window.scrollY) };
        for (const selector of scrollSelectors) {
            const element = document.querySelector(selector);
            if (element) scroll[selector] = Math.round(element.scrollTop);
        }
        const state = {
            ...contract.readSession(sessionStorage, localStorage),
            route: contract.safeLocation(location.href, location.origin),
            theme: localStorage.getItem("karaokeTheme") || "neonPurple",
            view: { tab: tabKey(tabs().find(button => button.classList.contains("active"))), scroll }
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

    function passwordFrom(body) {
        try {
            const password = JSON.parse(body).password;
            return typeof password === "string" && password.length <= 4096 ? password : "";
        } catch (_) { return ""; }
    }

    function responseReceived(path, method, status, data, password) {
        const passwordLogin = path === "/api/auth" && method === "POST";
        const qrLogin = path === "/api/auth/qr-session" && method === "GET";
        if ((passwordLogin || qrLogin) && status >= 200 && status < 300 && data?.token && ["member", "admin"].includes(data.level)) {
            send("credentials", { token: data.token, level: data.level, password: passwordLogin ? password : "", kind: qrLogin ? "qr" : "password" });
            renewalRequested = false;
        } else if (path && !passwordLogin && !qrLogin && status === 401 && !renewalRequested) {
            renewalRequested = true;
            send("expired");
        }
    }

    const originalFetch = window.fetch;
    if (originalFetch) window.fetch = async function (input, options) {
        const path = apiRequest(typeof input === "string" || input instanceof URL ? input : input.url);
        const method = (options?.method || input?.method || "GET").toUpperCase();
        const body = path === "/api/auth"
            ? (typeof options?.body === "string" ? Promise.resolve(options.body) : input instanceof Request ? input.clone().text() : Promise.resolve(""))
            : Promise.resolve("");
        const response = await originalFetch.apply(this, arguments);
        if (path) {
            const data = path === "/api/auth" || path === "/api/auth/qr-session" ? response.clone().json().catch(() => null) : Promise.resolve(null);
            Promise.all([data, body]).then(([result, text]) => responseReceived(path, method, response.status, result, passwordFrom(text))).catch(() => {});
        }
        return response;
    };

    const requests = new WeakMap();
    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
        requests.set(this, { method: String(method).toUpperCase(), path: apiRequest(url) });
        return originalOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
        const request = requests.get(this);
        if (request?.path) {
            const password = request.path === "/api/auth" ? passwordFrom(body) : "";
            this.addEventListener("load", () => {
                let data = null;
                if (request.path === "/api/auth" || request.path === "/api/auth/qr-session") {
                    try { data = this.responseType === "json" ? this.response : JSON.parse(this.responseText); } catch (_) { }
                }
                responseReceived(request.path, request.method, this.status, data, password);
                requests.delete(this);
            }, { once: true });
        }
        return originalSend.apply(this, arguments);
    };

    for (const method of ["setItem", "removeItem", "clear"]) {
        const original = Storage.prototype[method];
        Storage.prototype[method] = function (key) {
            const signingOut = this === sessionStorage && sessionStorage.getItem("authToken") &&
                (method === "clear" || method === "removeItem" && key === "authToken");
            const result = original.apply(this, arguments);
            if (signingOut) send("logout");
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
            scheduleSnapshot();
            return result;
        };
    }
    window.addEventListener("popstate", scheduleSnapshot);
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
            const dragging = !!document.querySelector(".playlist-item.dragging");
            if (dragging && !wasDragging) send("haptic");
            wasDragging = dragging;
            if (!restoring || restoreQueued) return;
            const available = tabs();
            if (!available.length && performance.now() - started < 15000 && !document.querySelector(".password-modal, input[type=password], .error-screen")) return;
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
        observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ["class"] });
        restoreView();
        setTimeout(() => { restoring = false; snapshot(); }, 15000);
        send("ready");
    }

    window.KaraokeAndroid = { snapshot, applyTheme, retryAuthentication: () => { renewalRequested = false; } };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", attach, { once: true });
    else attach();
})();