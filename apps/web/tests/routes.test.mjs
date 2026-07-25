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
  "src/app/topics/[slug]/page.tsx",
  "src/app/events/[id]/page.tsx",
  "src/app/favorites/page.tsx",
  "src/app/subscriptions/page.tsx",
  "src/app/research/page.tsx",
  "src/app/agent/page.tsx",
  "src/app/admin/sources/page.tsx",
  "src/app/admin/crawls/page.tsx",
  "src/app/admin/content/page.tsx",
  "src/app/admin/models/page.tsx",
  "src/app/admin/readiness/page.tsx",
  "src/app/admin/operations/page.tsx",
  "src/app/admin/reports/page.tsx",
  "src/app/admin/users/page.tsx",
  "src/app/admin/sources/[id]/page.tsx",
  "src/app/login/page.tsx",
  "src/app/register/page.tsx",
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
  assert.match(view, /REPORT_SECTION_LIMIT/);
  assert.match(view, /truncateSummary/);
  assert.match(view, /report\.source === "LIVE"/);
  assert.match(view, /60_000/);
  assert.doesNotMatch(view, /\b(?:688|919|148)\b/);
});

test("public email-code registration and login are discoverable", () => {
  const login = readFileSync("src/app/login/page.tsx", "utf8");
  const register = readFileSync("src/app/register/page.tsx", "utf8");
  assert.match(login, /\/auth\/code-login/);
  assert.match(login, /\/register/);
  assert.match(register, /\/auth\/email-codes/);
  assert.match(register, /\/auth\/register/);
});

test("M6 topics use persistent topic and public discovery APIs", () => {
  const page = readFileSync("src/app/topics/page.tsx", "utf8");
  const detail = readFileSync("src/app/topics/[slug]/page.tsx", "utf8");
  const discovery = readFileSync("src/lib/public-discovery.ts", "utf8");
  assert.match(page, /getPublicTopics/);
  assert.match(page, /\/topics\/\$\{topic\.slug\}/);
  assert.match(detail, /getPublicTopic/);
  assert.match(discovery, /\/api\/v1\/public/);
  assert.match(detail, /groupByDate/);
  assert.match(detail, /className="timeline"/);
  assert.doesNotMatch(detail, /Mock 质量分析/);
});

test("M7-M10 workspaces use real APIs and expose governance", () => {
  const workspaces = readFileSync("src/components/product-workspaces.tsx", "utf8");
  const models = readFileSync("src/app/admin/models/page.tsx", "utf8");
  const readiness = readFileSync("src/app/admin/readiness/page.tsx", "utf8");
  const operations = readFileSync("src/app/admin/operations/page.tsx", "utf8");
  assert.match(workspaces, /\/research\/query/);
  assert.match(workspaces, /stageTimingsMs/);
  assert.match(workspaces, /inline-citation/);
  assert.match(workspaces, /RAG_STAGES/);
  assert.match(workspaces, /evidenceStance/);
  assert.match(workspaces, /freshnessStatus/);
  assert.match(workspaces, /conflictDetected/);
  assert.match(workspaces, /\/subscriptions/);
  assert.match(workspaces, /\/agents\/runs/);
  assert.match(workspaces, /\/agents\/runs\/\$\{run\.id\}\/cancel/);
  assert.match(workspaces, /\/agents\/runs\/\$\{run\.id\}\/retry/);
  assert.match(workspaces, /安全重试/);
  assert.match(workspaces, /执行步骤/);
  assert.match(models, /\/admin\/ai\/evaluations\/run/);
  assert.match(models, /\/admin\/ai\/reindex/);
  assert.match(models, /\/admin\/ai\/metrics\?hours=/);
  assert.match(models, /\/admin\/ai\/evaluations\?limit=30/);
  assert.match(models, /慢查询诊断/);
  assert.match(models, /Provider 错误率/);
  assert.match(readiness, /\/admin\/readiness/);
  assert.match(operations, /\/agents\/approvals/);
  assert.doesNotMatch(workspaces, /Mock 质量分析/);
});

test("research restores every turn and does not overstate evidence verification", () => {
  const workspaces = readFileSync("src/components/product-workspaces.tsx", "utf8");
  const styles = readFileSync("src/app/styles/public-product.css", "utf8");
  assert.match(workspaces, /setSessionTurns\(view\.turns\)/);
  assert.match(workspaces, /本研究共 \{sessionTurns\.length\} 轮/);
  assert.match(workspaces, /timeRangeLabel/);
  assert.match(workspaces, /sourceCount<2/);
  assert.match(workspaces, /证据有限/);
  assert.match(workspaces, /当前证据不足以完成交叉验证/);
  assert.match(styles, /\.research-turns/);
  assert.match(styles, /\.evidence-limit-banner/);
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

test("direct Web access proxies authenticated Core API requests", () => {
  const proxy = readFileSync("src/app/api/core/[...path]/route.ts", "utf8");
  assert.match(proxy, /CORE_API_INTERNAL_URL/);
  assert.match(proxy, /path\.slice\(2\)/);
  assert.match(proxy, /request\.arrayBuffer/);
  assert.match(proxy, /getSetCookie/);
  assert.match(proxy, /cache-control/);
});

test("authenticated workspaces enforce a page-level session guard", () => {
  const workspaces = readFileSync("src/components/product-workspaces.tsx", "utf8");
  assert.match(workspaces, /function ProtectedWorkspace/);
  assert.match(workspaces, /useAuth/);
  assert.match(workspaces, /需要登录/);
  assert.match(workspaces, /returnTo=/);
  assert.match(workspaces, /WorkspaceNotice/);
  assert.match(workspaces, /onClose=.*setNotice/);
});

test("all privileged admin workspaces hide controls before authorization", () => {
  const guard = readFileSync("src/components/admin-permission-state.tsx", "utf8");
  for (const page of [
    "src/app/admin/models/page.tsx",
    "src/app/admin/operations/page.tsx",
    "src/app/admin/readiness/page.tsx",
    "src/app/admin/reports/page.tsx",
  ]) {
    const source = readFileSync(page, "utf8");
    assert.match(source, /useAuth/);
    assert.match(source, /AdminSessionLoading/);
    assert.match(source, /AdminPermissionState/);
    assert.match(source, /authLoading\|\|!allowed|authLoading \|\| !allowed/);
  }
  assert.match(guard, /登录或注册/);
  assert.match(guard, /returnTo=/);
});

test("navigation preserves public workspaces and hides management links from regular users", () => {
  const sidebar = readFileSync("src/components/sidebar.tsx", "utf8");
  const navigation = readFileSync("src/lib/navigation.ts", "utf8");
  assert.match(navigation, /label: "智能"/);
  assert.match(navigation, /label: "管理"/);
  assert.match(sidebar, /returnTo=/);
  assert.match(sidebar, /items\.filter\(\(item\) => canSeeNavigationItem/);
  assert.match(sidebar, /access === "operator".*isOperator/s);
  assert.match(sidebar, /filter\(\(group\) => group\.items\.length > 0\)/);
});

test("featured view uses the approved source taxonomy and real public content", () => {
  const page = readFileSync("src/app/page.tsx", "utf8");
  const feed = readFileSync("src/components/public-feed.tsx", "utf8");
  assert.match(page, /导出日报/);
  assert.match(page, /进入知识库/);
  assert.match(feed, /\["OFFICIAL", "官方"\]/);
  assert.match(feed, /\["RESEARCH", "论文"\]/);
  assert.match(feed, /\["COMMUNITY", "社区"\]/);
  assert.match(page, /getPublicEvents/);
  assert.match(feed, /个独立信源/);
});

test("featured and all feeds expose accessible daily folding with readable times", () => {
  const feed = readFileSync("src/components/public-feed.tsx", "utf8");
  const css = readFileSync("src/app/globals.css", "utf8");
  assert.match(feed, /collapsedDates/);
  assert.match(feed, /aria-expanded={!collapsed}/);
  assert.match(feed, /aria-controls={panelId}/);
  assert.match(feed, /date-toggle-label/);
  assert.match(css, /font-size: 14px/);
  assert.doesNotMatch(css, /\.timeline-time \{ font-size: 0/);
});

test("content detail exposes the article title as its single primary heading", () => {
  const detail = readFileSync("src/app/content/[id]/page.tsx", "utf8");
  assert.match(detail, /className="detail-toolbar"/);
  assert.equal((detail.match(/<h1>/g) ?? []).length, 1);
  assert.doesNotMatch(detail, /title="内容详情"/);
});

test("crawl monitoring distinguishes healthy no-change from failures", () => {
  const page = readFileSync("src/app/admin/crawls/page.tsx", "utf8");
  assert.match(page, /pollOutcome/);
  assert.match(page, /正常零新增/);
  assert.match(page, /上游失败/);
  assert.match(page, /内容结构失败/);
});

test("M6 search, server favorites and report editorial are real API flows", () => {
  const all = readFileSync("src/app/all/page.tsx", "utf8");
  const searchApi = readFileSync("src/lib/public-content.ts", "utf8");
  const favorite = readFileSync("src/components/favorite-button.tsx", "utf8");
  const favorites = readFileSync("src/components/favorites-view.tsx", "utf8");
  const editor = readFileSync("src/app/admin/reports/page.tsx", "utf8");
  assert.match(all, /searchPublicContents/);
  assert.match(searchApi, /\/api\/v1\/public\/search/);
  assert.match(favorite, /\/favorites\/CONTENT/);
  assert.doesNotMatch(favorite, /localStorage/);
  assert.match(favorites, /apiFetch/);
  assert.match(editor, /\/admin\/reports\/generate/);
  assert.match(editor, /\/publish/);
});

test("public feeds preserve cursor pagination while spanning daily groups", () => {
  const api = readFileSync("src/lib/public-content.ts", "utf8");
  const feed = readFileSync("src/components/public-feed.tsx", "utf8");
  const card = readFileSync("src/components/public-content-card.tsx", "utf8");
  assert.match(api, /INITIAL_DATE_GROUPS = 2/);
  assert.match(api, /const limit = featured \? 12 : 50/);
  assert.match(api, /countDateGroups\(items\)/);
  assert.match(feed, /加载更多内容/);
  assert.match(feed, /nextCursor/);
  assert.match(card, /truncateSummary/);
});
