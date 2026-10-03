const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
    testDir: "tests/browser",
    outputDir: process.env.KARAOKE_FRONTEND_BUILD ? "test-results/server" : "test-results/fixture",
    timeout: 30000,
    fullyParallel: false,
    workers: 1,
    reporter: [["list"], ["html", { open: "never" }]],
    use: { baseURL: "http://127.0.0.1:4178", channel: "chromium", screenshot: "only-on-failure", trace: "retain-on-failure" },
    projects: [
        { name: "phone", use: { viewport: { width: 360, height: 800 }, isMobile: true, hasTouch: true } },
        { name: "tablet", use: { viewport: { width: 800, height: 1000 }, hasTouch: true } }
    ],
    webServer: { command: "node tests/browser/fixture-server.cjs", url: "http://127.0.0.1:4178", reuseExistingServer: false }
});