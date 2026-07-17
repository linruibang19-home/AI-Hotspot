import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";

const expectedPages = [
  "src/app/page.tsx",
  "src/app/all/page.tsx",
  "src/app/content/[id]/page.tsx",
  "src/app/reports/page.tsx",
  "src/app/daily/page.tsx",
  "src/app/weekly/page.tsx",
  "src/app/monthly/page.tsx",
  "src/app/topics/page.tsx",
  "src/app/favorites/page.tsx",
  "src/app/subscriptions/page.tsx",
  "src/app/research/page.tsx",
  "src/app/agent/page.tsx",
  "src/app/admin/sources/page.tsx",
  "src/app/admin/crawls/page.tsx",
  "src/app/admin/content/page.tsx",
  "src/app/admin/models/page.tsx",
  "src/app/admin/users/page.tsx",
  "src/app/admin/sources/[id]/page.tsx",
  "src/app/login/page.tsx",
  "src/app/invite/[token]/page.tsx",
];

test("M1 route skeleton is complete", () => {
  for (const page of expectedPages) assert.equal(existsSync(page), true, `${page} is missing`);
});

test("web exposes a server-side Core API health bridge", () => {
  assert.equal(existsSync("src/app/api/system/health/route.ts"), true);
  const bridge = readFileSync("src/app/api/system/health/route.ts", "utf8");
  assert.match(bridge, /CORE_API_INTERNAL_URL/);
  assert.match(bridge, /x-correlation-id/);
});

test("excluded collectors never enter navigation", () => {
  const navigation = readFileSync("src/lib/navigation.ts", "utf8").toLowerCase();
  assert.equal(navigation.includes("wechat"), false);
  assert.equal(navigation.includes("微信公众号"), false);
  assert.equal(navigation.includes("x api"), false);
});

test("M2 identity and source management use the real Core API", () => {
  assert.equal(existsSync("src/components/auth-provider.tsx"), true);
  assert.equal(existsSync("src/lib/api.ts"), true);
  const sources = readFileSync("src/app/admin/sources/page.tsx", "utf8");
  assert.match(sources, /apiFetch/);
  assert.doesNotMatch(sources, /M1 Mock 数据/);
  const navigation = readFileSync("src/lib/navigation.ts", "utf8");
  assert.match(navigation, /access: "operator"/);
  assert.match(navigation, /access: "admin"/);
});

test("M3 public feeds use the real public content API", () => {
  const featured = readFileSync("src/app/page.tsx", "utf8");
  const all = readFileSync("src/app/all/page.tsx", "utf8");
  const api = readFileSync("src/lib/public-content.ts", "utf8");
  assert.match(featured, /getPublicContents/);
  assert.match(all, /getPublicContents/);
  assert.match(api, /\/api\/v1\/public\/contents/);
  assert.doesNotMatch(featured, /featuredItems/);
});

test("report routes render the real Core report API", () => {
  const page = readFileSync("src/app/reports/page.tsx", "utf8");
  const api = readFileSync("src/lib/public-report.ts", "utf8");
  const view = readFileSync("src/components/report-view.tsx", "utf8");
  assert.match(page, /getPublicReport/);
  assert.match(api, /\/api\/v1\/public\/reports/);
  assert.match(view, /report\.storyCount/);
  assert.doesNotMatch(view, /\b(?:688|919|148)\b/);
});

test("topic cards navigate to real public-content filters", () => {
  const page = readFileSync("src/app/topics/page.tsx", "utf8");
  const topics = readFileSync("src/lib/topics.ts", "utf8");
  const all = readFileSync("src/app/all/page.tsx", "utf8");
  assert.match(page, /\/all\?query=/);
  assert.match(topics, /Google \/ Gemini/);
  assert.match(topics, /通义千问 Qwen/);
  assert.match(all, /initialQuery/);
});

test("M5 content governance uses real queues, events and ticket APIs", () => {
  const page = readFileSync("src/app/admin/content/page.tsx", "utf8");
  assert.match(page, /apiFetch<Response>\("\/admin\/content\?limit=100"\)/);
  assert.match(page, /\/admin\/content\/events\?limit=100/);
  assert.match(page, /\/admin\/content\/tickets\?limit=100/);
  assert.match(page, /MARK_UNCONFIRMED/);
  assert.match(page, /DEBUNK/);
  assert.doesNotMatch(page, /WorkspacePage/);
});
