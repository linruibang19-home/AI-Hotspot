import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";

const expectedPages = [
  "src/app/page.tsx",
  "src/app/all/page.tsx",
  "src/app/reports/page.tsx",
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
