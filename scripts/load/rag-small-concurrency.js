import http from "k6/http";
import exec from "k6/execution";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "http://nginx";
const email = __ENV.BOOTSTRAP_ADMIN_EMAIL;
const password = __ENV.BOOTSTRAP_ADMIN_PASSWORD;
const ragLatency = new Trend("rag_latency_ms", true);
const ragErrors = new Rate("rag_errors");
const ragQualityFailures = new Rate("rag_quality_failures");

const questions = [
  "过去 7 天 Agent 产品有哪些重要变化？请区分官方发布与媒体观点。",
  "OpenAI 与 Anthropic 最近有哪些重要模型动态？",
  "RAG 检索与重排技术最近有哪些重要进展？",
  "Model Context Protocol（MCP）生态最近有哪些更新？",
];

export const options = {
  scenarios: {
    rag_2_vus: {
      executor: "per-vu-iterations",
      vus: 2,
      iterations: 2,
      maxDuration: "90s",
    },
  },
  thresholds: {
    rag_errors: ["rate<0.01"],
    rag_quality_failures: ["rate<0.01"],
    rag_latency_ms: ["p(95)<12000"],
  },
  summaryTrendStats: ["count", "avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function login() {
  const csrf = http.get(`${baseUrl}/api/core/api/v1/auth/csrf`);
  const csrfPayload = csrf.json();
  const headers = {
    "Content-Type": "application/json",
    [csrfPayload.headerName]: csrfPayload.token,
  };
  const response = http.post(
    `${baseUrl}/api/core/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers },
  );
  return check(response, { "admin login 200": (result) => result.status === 200 });
}

export default function () {
  if (!email || !password || !login()) {
    ragErrors.add(true);
    exec.test.abort("RAG load test requires valid bootstrap admin credentials");
  }
  const csrf = http.get(`${baseUrl}/api/core/api/v1/auth/csrf`).json();
  const question = questions[exec.scenario.iterationInTest % questions.length];
  const response = http.post(
    `${baseUrl}/api/core/api/v1/research/query`,
    JSON.stringify({ sessionId: null, question, filters: {} }),
    {
      headers: {
        "Content-Type": "application/json",
        [csrf.headerName]: csrf.token,
      },
      timeout: "60s",
      tags: { endpoint: "rag_query" },
    },
  );
  const transportPassed = check(response, {
    "RAG status 200": (result) => result.status === 200,
  });
  let qualityPassed = false;
  if (transportPassed) {
    const result = response.json();
    qualityPassed =
      result.answerStatus === "SUCCEEDED" &&
      (result.citations || []).length >= 2 &&
      Number(result.diagnostics?.citationCoverage || 0) >= 0.8;
  }
  check(response, { "RAG answer quality": () => qualityPassed });
  ragLatency.add(response.timings.duration);
  ragErrors.add(!transportPassed);
  ragQualityFailures.add(!qualityPassed);
}
