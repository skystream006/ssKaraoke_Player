const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const build = process.env.KARAOKE_FRONTEND_BUILD ? path.resolve(process.env.KARAOKE_FRONTEND_BUILD) : null;
const contentTypes = { ".html": "text/html", ".js": "application/javascript", ".css": "text/css", ".png": "image/png", ".svg": "image/svg+xml", ".json": "application/json", ".woff2": "font/woff2" };
const server = http.createServer((request, response) => {
    const address = new URL(request.url, "http://127.0.0.1:4178");
    if (address.pathname.startsWith("/socket.io/")) {
        response.writeHead(404);
        response.end();
        return;
    }
    if (address.pathname === "/test-icon.png") {
        response.writeHead(200, { "Content-Type": "image/png" });
        fs.createReadStream(path.resolve(__dirname, "../../app/src/main/res/drawable/app_icon.png")).pipe(response);
        return;
    }
    let file = path.join(__dirname, "fixture.html");
    if (build) {
        const candidate = path.resolve(build, "." + decodeURIComponent(address.pathname));
        if (!candidate.startsWith(build + path.sep) && candidate !== build) {
            response.writeHead(403); response.end(); return;
        }
        file = fs.existsSync(candidate) && fs.statSync(candidate).isFile() ? candidate : path.join(build, "index.html");
    }
    response.writeHead(200, { "Content-Type": contentTypes[path.extname(file)] || "application/octet-stream" });
    fs.createReadStream(file).pipe(response);
});
server.listen(4178, "127.0.0.1", () => console.log(build ? "Testing the local karaoke frontend build on port 4178" : "Testing the standalone adapter fixture on port 4178"));