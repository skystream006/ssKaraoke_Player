const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const workflow = name => YAML.parse(fs.readFileSync(path.resolve(__dirname, `../.github/workflows/${name}.yml`), "utf8"));

test("release is manual, builds full main history, verifies signing, and never overwrites a release", () => {
    const release = workflow("manual-release");
    assert.deepEqual(Object.keys(release.on), ["workflow_dispatch"]);
    assert.equal(release.permissions.contents, "write");
    const steps = release.jobs.release.steps;
    const checkout = steps.find(step => step.uses?.startsWith("actions/checkout@"));
    assert.deepEqual(checkout.with, { ref: "main", "fetch-depth": 0 });
    assert.ok(steps.some(step => step.run?.includes("apksigner\" verify")));
    const publish = steps.find(step => step.name === "Publish latest GitHub release");
    assert.match(publish.run, /gh release view/);
    assert.match(publish.run, /--latest/);
    assert.doesNotMatch(publish.run, /--clobber|release delete/);
    assert.ok(steps.some(step => step.if === "always()" && step.run?.includes("distribution.jks")));
});

test("PR reminder never checks out or executes pull request code", () => {
    const reminder = workflow("release-reminder");
    assert.deepEqual(reminder.on.pull_request_target.types, ["closed"]);
    assert.equal(reminder.jobs.remind.if, "github.event.pull_request.merged == true");
    assert.deepEqual(reminder.permissions, { "pull-requests": "write" });
    assert.ok(reminder.jobs.remind.steps.every(step => !step.uses));
    const script = reminder.jobs.remind.steps[0].run;
    assert.match(script, /manual-release-reminder/);
    assert.doesNotMatch(script, /checkout|git clone|github\.event\.pull_request\.(title|body|head)/);
});

test("CI has read-only permissions and covers native and browser tests", () => {
    const checks = workflow("android");
    assert.deepEqual(checks.permissions, { contents: "read" });
    const commands = checks.jobs.verify.steps.map(step => step.run || "").join("\n");
    assert.match(commands, /npm run test:browser/);
    assert.match(commands, /:app:testDebugUnitTest/);
    assert.match(commands, /:app:lintDebug/);
    assert.match(commands, /:app:assembleDebugAndroidTest/);
});